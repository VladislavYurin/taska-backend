package ru.taska.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.*;
import tools.jackson.databind.ObjectMapper;

/** Project transactions never span the binding RPC. */
@Service
@RequiredArgsConstructor
public class IssueTypeCreationTransactions {
    private final ProjectIssueTypeRepository types;
    private final ProjectTypeOperationRepository operations;
    private final IssueTypeCreationRepository journal;
    private final ProjectWriteGuard writeGuard;
    private final ObjectMapper mapper;
    public record Created(ProjectIssueType type,UUID workflowId) { }

    @Transactional
    public Mono<IssueTypeCreation> begin(UUID project,UUID actor,String request,String node,String code,String name,String color,
            String icon,String shape,Integer sort,UUID workflow,ActionMetadata attribution) {
        return Mono.defer(()->{
            if(request==null||request.isBlank()||request.length()>256)throw new DomainException(DomainStatus.INVALID_ARGUMENT,"A bounded X-Request-Id is required");
            ProjectIssueTypeService.validateCode(code);
            var type=ProjectIssueTypeService.validate(new ProjectIssueType(project,code,name,color,icon,shape==null?"square":shape,sort==null?100:sort,null,null,UUID.randomUUID()));
            String hash=hash(type,workflow);
            return authorize(project,actor).then(journal.request(project,actor,request)).flatMap(saved->saved.commandHash().equals(hash)?Mono.just(saved):Mono.error(
                    new DomainException(DomainStatus.ALREADY_EXISTS,"X-Request-Id was used with another command",Map.of("reason","REQUEST_ID_REUSED"))))
                    .switchIfEmpty(Mono.defer(()->operations.requireFree(project)
                            .then(types.find(project,code)).flatMap(existing->Mono.<IssueTypeCreation>error(new DomainException(DomainStatus.ALREADY_EXISTS,"Issue type already exists")))
                            .switchIfEmpty(types.list(project).next().map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                                    .flatMap(first->{
                                        if (workflow==null && first.isEmpty()) return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                                "Choose a workflow for the first project issue type",Map.of("reason","ISSUE_TYPE_WORKFLOW_REQUIRED")));
                                        var item=new IssueTypeCreation(UUID.randomUUID(),project,actor,request,node,hash,type,workflow,first.map(ProjectIssueType::code).orElse(null),first.map(ProjectIssueType::generation).orElse(null),null,"PENDING",attribution,null,null,Map.of());
                                        return operations.reserve(project,item.id(),"CREATE_TYPE").then(journal.insert(item));
                                    }))));
        });
    }
    @Transactional
    public Mono<Created> complete(IssueTypeCreation original,UUID workflow) {
        return lock(original.projectId()).then(require(original)).flatMap(item->{
            if(item.state().equals("COMPLETED"))return Mono.just(new Created(item.type(),item.resolvedWorkflowId()));
            if(item.state().equals("CANCELLED"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Type creation was cancelled"));
            return types.find(item.projectId(),item.type().code()).flatMap(existing->existing.generation().equals(item.type().generation())?Mono.just(existing):Mono.error(
                            new DomainException(DomainStatus.ABORTED,"Issue type lifecycle changed")))
                    .switchIfEmpty(types.insertWithGeneration(item.type()))
                    .flatMap(saved->types.syncLegacySettings(item.projectId(),item.actor()).then(journal.completed(item,workflow))
                            .then(operations.close(item.projectId(),item.id(),"CREATE_TYPE")).thenReturn(new Created(item.type(),workflow)));
        });
    }
    @Transactional
    public Mono<IssueTypeCreation> requestCancellation(IssueTypeCreation item,DomainException error) {
        return lock(item.projectId()).then(require(item)).flatMap(current->journal.cancelling(current,error));
    }
    @Transactional
    public Mono<Void> cancel(IssueTypeCreation original) {
        return lock(original.projectId()).then(require(original)).flatMap(item->{
            if(item.state().equals("COMPLETED"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Type creation already completed"));
            return journal.cancelled(item).then(operations.close(item.projectId(),item.id(),"CREATE_TYPE"));
        });
    }
    private Mono<IssueTypeCreation> require(IssueTypeCreation item) {
        return journal.find(item.projectId(),item.id()).switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Creation intent not found")));
    }
    private Mono<Void> lock(UUID project) {
        return types.lockProject(project).switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found"))).then();
    }
    private Mono<Void> authorize(UUID project,UUID actor) {
        return lock(project).then(types.lockActorRole(project,actor)).filter(role->role==ProjectRole.ADMIN)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Project ADMIN role is required"))).then(writeGuard.check(project,actor));
    }
    private String hash(ProjectIssueType type,UUID workflow) {
        var content=new LinkedHashMap<String,Object>();content.put("code",type.code());content.put("name",type.name());content.put("color",type.color());
        content.put("icon",type.icon());content.put("shape",type.shape());content.put("sortOrder",type.sortOrder());content.put("workflowId",workflow==null?null:workflow.toString());
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(content).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
