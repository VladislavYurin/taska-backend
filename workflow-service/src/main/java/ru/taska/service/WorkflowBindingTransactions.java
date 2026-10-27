package ru.taska.service;

import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.*;
import ru.taska.domain.WorkflowBindingChange.Move;
import ru.taska.domain.WorkflowBindingChange.Plan;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowCreationMapper;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.repository.*;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import tools.jackson.databind.ObjectMapper;

/** Short local transactions only: no issue RPC is called while holding the workflow/project lock. */
@Service
@RequiredArgsConstructor
public class WorkflowBindingTransactions {
    private final WorkflowBindingChangeRepository journal;
    private final WorkflowWriteFence fence;
    private final WorkflowRepository workflows;
    private final WorkflowBindingRepository bindings;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final ProjectRoleChecker roles;
    private final GrpcProjectServiceClient projects;
    private final WorkflowProperties properties;
    private final WorkflowCreationValidator validator;
    private final WorkflowCreationMapper creationMapper;
    private final WorkflowMapper mapper;
    private final ObjectMapper json;
    private final DatabaseClient database;

    @Transactional
    public Mono<WorkflowBindingChange> create(Header header, UUID actor, WorkflowCreationDto dto) {
        return Mono.deferContextual(context -> {
            validateHeader(header);
            var violations=validator.validateDto(dto);
            if (!violations.isEmpty()) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                    validator.buildViolationMessage(violations)));
            validateMappings(dto.getStatusMappings());
            var command=new TreeMap<String,Object>();
            command.put("kind","CREATE");command.put("name",dto.getName().strip());
            command.put("types",dto.getIssueTypes().stream().map(IssueType::name).sorted().toList());
            command.put("statuses",dto.getStatuses());command.put("transitions",dto.getTransitions());
            command.put("mappings",new TreeMap<>(dto.getStatusMappings()));
            return begin(header,actor,dto.getProjectId(),json.writeValueAsString(command),ActionMetadata.current(context))
                    .switchIfEmpty(Mono.defer(() -> projects.listIssueTypeDetails(header.getRequestId(),header.getNodeId(),dto.getProjectId(),actor)
                            .flatMap(registry -> {
                                requireRegistry(registry,dto.getIssueTypes().stream().map(IssueType::name).toList());
                                return saveGraph(dto).flatMap(target -> prepare(header,actor,dto.getProjectId(),target,
                                        dto.getIssueTypes().stream().map(IssueType::name).toList(),dto.getStatusMappings(),registry,
                                        "CREATE",json.writeValueAsString(command),dto.getName().strip(),ActionMetadata.current(context)));
                            })));
        });
    }

    @Transactional
    public Mono<WorkflowBindingChange> replace(Header header, UUID project, UUID workflow, UUID actor,
            Integer version, String name, List<String> requested, Map<String,String> mapping) {
        return Mono.deferContextual(context -> {
            validateHeader(header);
            if (requested==null || requested.isEmpty()) return Mono.error(error(DomainStatus.INVALID_ARGUMENT,
                    "WORKFLOW_ISSUE_TYPES_EMPTY","Workflow must use at least one issue type"));
            if (version==null || version<1) return Mono.error(error(DomainStatus.INVALID_ARGUMENT,
                    "WORKFLOW_VERSION_REQUIRED","version is required when replacing issueTypes"));
            if (new HashSet<>(requested).size()!=requested.size()) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Duplicate issue type"));
            validateMappings(mapping);
            var command=new TreeMap<String,Object>();command.put("kind","REPLACE_TYPES");command.put("workflow",workflow);
            command.put("version",version);command.put("name",name==null?null:name.strip());
            command.put("types",requested.stream().sorted().toList());command.put("mappings",new TreeMap<>(mapping));
            return begin(header,actor,project,json.writeValueAsString(command),ActionMetadata.current(context))
                    .switchIfEmpty(Mono.defer(() -> projects.listIssueTypeDetails(header.getRequestId(),header.getNodeId(),project,actor)
                            .flatMap(registry -> {
                                requireRegistry(registry,requested);
                                return workflows.findByIdForUpdate(workflow).filter(WorkflowEntity::isActive)
                                        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Workflow not found in project")))
                                        .flatMap(target -> bindings.findByWorkflowId(workflow).collectList().flatMap(current -> {
                                            WorkflowOwnership.requireOwned(target,current,project,properties.defaultProjectId());
                                            if (target.getVersion()!=version) return Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow version changed"));
                                            String desired=name==null?target.getName():name.strip();
                                            if (desired.isBlank()) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Workflow name must not be blank"));
                                            if (desired.length()>100) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Workflow name is too long"));
                                            return prepare(header,actor,project,target,requested,mapping,registry,"REPLACE_TYPES",
                                                    json.writeValueAsString(command),desired,ActionMetadata.current(context));
                                        }));
                            })));
        });
    }

    /** Empty means a fresh operation may be prepared; existing same-key replay returns its immutable journal. */
    private Mono<WorkflowBindingChange> begin(Header header, UUID actor, UUID project, String command, ActionMetadata metadata) {
        return roles.checkProjectWriteRole(header.getRequestId(),header.getNodeId(),project,actor,properties.allowedRoles().editWorkflowRoles())
                .then(Mono.defer(() -> fence.lockProject(project)))
                .then(Mono.defer(() -> journal.byRequest(project,actor,header.getRequestId())))
                .flatMap(item -> journal.matchesCommand(item,command).flatMap(matches -> matches?Mono.just(item)
                        :Mono.error(error(DomainStatus.ALREADY_EXISTS,"WORKFLOW_OPERATION_REQUEST_REUSED","X-Request-Id already identifies another workflow command"))))
                .switchIfEmpty(Mono.defer(() -> fence.checkAvailable(project).then(Mono.empty())));
    }

    private Mono<WorkflowEntity> saveGraph(WorkflowCreationDto dto) {
        return workflows.save(WorkflowEntity.builder().name(dto.getName().strip()).ownerProjectId(dto.getProjectId()).version(1).active(false).build())
                .flatMap(saved -> statuses.saveAll(dto.getStatuses().stream().map(s -> creationMapper.toStatusEntity(saved.getId(),s)).toList())
                        .collectList().flatMap(savedStatuses -> {
                            var ids=savedStatuses.stream().collect(Collectors.toMap(s->s.getStatusKey(),s->s.getId()));
                            return transitions.saveAll(dto.getTransitions().stream().map(t -> creationMapper.toTransitionEntity(saved.getId(),ids,t)).toList())
                                    .then(Mono.just(saved));
                        }));
    }

    private Mono<WorkflowBindingChange> prepare(Header header, UUID actor, UUID project, WorkflowEntity target,
            List<String> requested, Map<String,String> mapping, List<ProjectIssueTypeResponse> registry,
            String kind, String command, String desiredName, ActionMetadata attribution) {
        return Mono.zip(bindings.findByWorkflowId(target.getId()).collectList(),
                        workflows.findWorkflowForProject(properties.defaultProjectId(),"TASK")
                                .filter(WorkflowEntity::isActive).switchIfEmpty(Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Default workflow is missing"))))
                .flatMap(data -> {
                    var current=data.getT1();var fallback=data.getT2();
                    List<String> desired=registry.stream().map(ProjectIssueTypeResponse::getCode).filter(requested::contains).toList();
                    var scope=registry.stream().filter(type -> desired.contains(type.getCode()) || current.stream()
                            .anyMatch(b -> b.getProjectId().equals(project)&&IssueTypeBindingIdentity.matches(b,type))).toList();
                    return Mono.zip(statusKeys(target.getId()),statusKeys(fallback.getId())).flatMap(keys -> {
                        var versions=new LinkedHashMap<String,Integer>();versions.put(target.getId().toString(),target.getVersion());
                        var budget=new PlanBudget();
                        return Flux.fromIterable(scope).concatMap(type -> {
                            UUID generation=IssueTypeBindingIdentity.generation(type);
                            return workflows.findWorkflowForProjectGeneration(project,type.getCode(),generation,
                                            generation.equals(IssueTypeBindingIdentity.initialGeneration(project,type.getCode())))
                                    .switchIfEmpty(Mono.just(fallback)).flatMap(source -> {
                                        UUID destination=desired.contains(type.getCode())?target.getId():fallback.getId();
                                        List<String> valid=desired.contains(type.getCode())?keys.getT1():keys.getT2();
                                        budget.include(destination,valid);
                                        if (source.getId().equals(fallback.getId())) return Mono.just(new Move(type.getCode(),generation,source.getId(),destination,valid));
                                        return workflows.findByIdForUpdate(source.getId()).flatMap(locked -> bindings.findByWorkflowId(source.getId()).collectList()
                                                .map(sourceBindings -> {
                                                    WorkflowOwnership.requireOwned(locked,sourceBindings,project,properties.defaultProjectId());
                                                    if (!source.getId().equals(destination)) versions.put(source.getId().toString(),locked.getVersion());
                                                    return new Move(type.getCode(),generation,source.getId(),destination,valid);
                                                }));
                                    });
                        }).collectList().flatMap(moves -> {
                            var item=new WorkflowBindingChange(UUID.randomUUID(),project,target.getId(),actor,kind,
                                    header.getRequestId(),header.getNodeId(),attribution,command,
                                    new Plan(desired,moves,Map.copyOf(versions),Map.copyOf(mapping),desiredName),"PENDING",null,null,Map.of(),null,false);
                            // The cheap budget runs first; only bounded plans may allocate expanded JSON/protobuf.
                            if (item.intent().getSerializedSize()>3*1024*1024
                                    || json.writeValueAsString(item.plan()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>3*1024*1024)
                                return Mono.error(planTooLarge());
                            return journal.insert(item);
                        });
                    });
                });
    }

    private Mono<List<String>> statusKeys(UUID workflow) {
        return statuses.findActiveByWorkflowId(workflow).map(s -> s.getStatusKey()).sort().collectList();
    }

    @Transactional
    public Mono<ProjectWorkflow> complete(WorkflowBindingChange item) {
        return fence.lockProject(item.project()).then(Mono.defer(() -> journal.get(item.project(),item.id())))
                .flatMap(latest -> {
                    if (latest.state().equals("COMPLETED")) return Mono.fromCallable(() -> ProjectWorkflow.parseFrom(latest.result()));
                    if (!List.of("PENDING","CANCELLING").contains(latest.state())) return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Workflow receipt conflicts with terminal journal"));
                    return Flux.fromIterable(latest.plan().versions().entrySet()).concatMap(entry -> {
                        boolean newTarget=latest.kind().equals("CREATE")&&entry.getKey().equals(latest.workflow().toString());
                        var sql=database.sql("UPDATE taska.workflows SET owner_project_id=:project,name=CASE WHEN id=:target THEN :name ELSE name END,"
                                +"version=version+"+(newTarget?"0":"1")+",is_active=true,updated_at=now() WHERE id=:id AND version=:version")
                                .bind("project",latest.project()).bind("target",latest.workflow()).bind("name",latest.plan().desiredName())
                                .bind("id",UUID.fromString(entry.getKey())).bind("version",entry.getValue());
                        return sql.fetch().rowsUpdated().flatMap(count -> count==1?Mono.empty():Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Prepared workflow version changed")));
                    }).thenMany(Flux.fromIterable(latest.plan().moves()).concatMap(move -> applyBinding(latest.project(),move)))
                            .then(Mono.defer(() -> result(latest)))
                            .flatMap(result -> journal.completed(latest,result.toByteArray()).thenReturn(result));
                });
    }

    private Mono<Void> applyBinding(UUID project, Move move) {
        return workflows.findWorkflowForProject(properties.defaultProjectId(),"TASK").flatMap(fallback -> {
            if (move.targetWorkflow().equals(fallback.getId())) return database.sql("DELETE FROM taska.workflow_bindings WHERE project_id=:project AND issue_type=:type")
                    .bind("project",project).bind("type",move.code()).fetch().rowsUpdated().then();
            return database.sql("""
                    INSERT INTO taska.workflow_bindings(project_id,issue_type,workflow_id,issue_type_generation)
                    VALUES(:project,:type,:workflow,:generation)
                    ON CONFLICT(project_id,issue_type) DO UPDATE SET workflow_id=EXCLUDED.workflow_id,
                        issue_type_generation=EXCLUDED.issue_type_generation,updated_at=now()
                    WHERE taska.workflow_bindings.workflow_id<>EXCLUDED.workflow_id
                        OR taska.workflow_bindings.issue_type_generation IS DISTINCT FROM EXCLUDED.issue_type_generation
                    """).bind("project",project).bind("type",move.code()).bind("workflow",move.targetWorkflow())
                    .bind("generation",move.generation()).fetch().rowsUpdated().then();
        });
    }

    private Mono<ProjectWorkflow> result(WorkflowBindingChange item) {
        return workflows.findById(item.workflow()).flatMap(workflow -> Mono.zip(statuses.findActiveByWorkflowId(item.workflow()).collectList(),
                        transitions.findByWorkflowIdOrderBySortOrderAsc(item.workflow()).collectList())
                .map(data -> mapper.toProjectWorkflowProto(new ProjectWorkflowAggregate(new WorkflowAggregate(workflow,data.getT1(),data.getT2()),
                        item.plan().desiredTypes().stream().map(IssueType::valueOf).toList(),false))));
    }

    @Transactional
    public Mono<WorkflowBindingChange> cancel(WorkflowBindingChange item) {
        return fence.lockProject(item.project()).then(Mono.defer(() -> journal.get(item.project(),item.id())))
                .flatMap(latest -> {
                    if (latest.state().equals("COMPLETED")||latest.state().equals("CANCELLED")) return Mono.just(latest);
                    if (!latest.state().equals("CANCELLING")) return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Workflow cancellation is not durable"));
                    Mono<Void> cleanup=latest.kind().equals("CREATE")?database.sql("DELETE FROM taska.workflows WHERE id=:id AND owner_project_id=:project AND is_active=false")
                            .bind("id",latest.workflow()).bind("project",latest.project()).fetch().rowsUpdated().then():Mono.empty();
                    return cleanup.then(journal.cancelled(latest)).then(journal.get(item.project(),item.id()));
                });
    }

    private void requireRegistry(List<ProjectIssueTypeResponse> registry, List<String> requested) {
        WorkflowWriteFence.requireWritableRegistry(registry);
        if (requested.stream().anyMatch(code -> !code.matches("^[A-Z][A-Z0-9_]{1,31}$")
                || registry.stream().noneMatch(type -> type.getCode().equals(code))))
            throw error(DomainStatus.INVALID_ARGUMENT,"WORKFLOW_ISSUE_TYPE_NOT_IN_PROJECT","Issue type is not registered in this project");
        registry.forEach(IssueTypeBindingIdentity::generation);
    }
    /** Status keys are ASCII by contract; their repeated cost is computed once per destination graph. */
    private static final class PlanBudget {
        private final Map<UUID,Long> weights=new HashMap<>();
        private long used;
        void include(UUID workflow,List<String> keys) {
            long statuses=weights.computeIfAbsent(workflow,id -> keys.stream().mapToLong(key -> key.length()+4L).sum());
            used+=300L+statuses;
            if (used>2*1024*1024) throw planTooLarge();
        }
    }
    private static DomainException planTooLarge() {
        return error(DomainStatus.INVALID_ARGUMENT,"WORKFLOW_BINDING_PLAN_TOO_LARGE",
                "The expanded workflow binding plan exceeds the internal transport limit; reduce the type or status scope");
    }

    private static void validateHeader(Header header) {
        if (header.getRequestId().isBlank() || header.getRequestId().length()>200 || header.getNodeId().isBlank() || header.getNodeId().length()>100)
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,"A bounded requestId and nodeId are required");
    }
    private static void validateMappings(Map<String,String> mappings) {
        if (mappings==null || mappings.size()>5000 || mappings.entrySet().stream().anyMatch(e -> e.getKey()==null || e.getValue()==null
                || !e.getKey().matches("^[A-Z][A-Z0-9_]{1,63}$") || !e.getValue().matches("^[A-Z][A-Z0-9_]{1,63}$")))
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid workflow status mapping");
    }
    static DomainException error(DomainStatus status,String reason,String message) { return new DomainException(status,message,Map.of("reason",reason)); }
}
