package ru.taska.service;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.IssueTypeDeletion;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueTypeDeletionRepository;
import ru.taska.repository.ProjectIssueTypeRepository;

/** Short project transactions; no network calls or open transaction spans the issue commit. */
@Service
@RequiredArgsConstructor
public class IssueTypeDeletionTransactions {
  private final ProjectIssueTypeRepository types;
  private final IssueTypeDeletionRepository journal;
  private final ProjectWriteGuard writeGuard;
  private final ru.taska.repository.ProjectTypeOperationRepository operations;

  @Transactional
  public Mono<IssueTypeDeletion> begin(UUID project,UUID actor,String source,String target,
      Map<String,String> mapping,UUID expected,String request,String node,ActionMetadata attribution) {
    return Mono.defer(()-> {
      validate(source,target,mapping,expected);
      return authorize(project,actor).then(journal.pending(project))
          .flatMap(pending->pending.source().equals(source)&&pending.matches(target,mapping,expected)
              ? Mono.just(pending):Mono.error(new DomainException(DomainStatus.ABORTED,"Another project issue-type operation is pending",
                  Map.of("reason","ISSUE_TYPE_OPERATION_PENDING"))))
          .switchIfEmpty(Mono.defer(()->types.find(project,source)
              .flatMap(current->beginNew(project,actor,current,target,mapping,expected,request,node,attribution))
              .switchIfEmpty(Mono.defer(()->target==null?journal.completedRequest(project,actor,source,request).switchIfEmpty(missing()):journal.completed(project,source,target,mapping,expected)
                  .switchIfEmpty(missing())))));
    });
  }

  private Mono<IssueTypeDeletion> beginNew(UUID project,UUID actor,ProjectIssueType current,String target,
      Map<String,String> mapping,UUID expected,String request,String node,ActionMetadata attribution) {
    Mono<Void> generationCheck=Mono.empty();
    if(target!=null) {
      if(expected!=null&&!expected.equals(current.generation())) return Mono.error(changed(current));
      if(expected==null)generationCheck=journal.hasOlderLifecycle(project,current.code(),current.generation())
          .flatMap(reused->reused?Mono.error(new DomainException(DomainStatus.ABORTED,
              "This issue type code was recreated; supply expectedGeneration from the current registry",
              Map.of("reason","ISSUE_TYPE_GENERATION_REQUIRED","generation",current.generation().toString()))):Mono.empty());
    }
    Mono<UUID> targetGeneration=target==null?Mono.just(new UUID(0,0)):types.find(project,target)
        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Target issue type is not registered in this project",
            Map.of("reason","ISSUE_TYPE_TARGET_INVALID"))))
        .map(ProjectIssueType::generation);
    return generationCheck.then(targetGeneration).flatMap(generation->types.list(project).count().flatMap(count->{
      if(count<=1)return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Cannot delete the last issue type",
          Map.of("reason","LAST_ISSUE_TYPE")));
      var item=new IssueTypeDeletion(UUID.randomUUID(),project,current.code(),current.generation(),target,
          target==null?null:generation,actor,Map.copyOf(mapping),"PENDING",request,node,attribution,null,null,Map.of(),false);
      return operations.reserve(project,item.id(),"DELETE_TYPE").then(journal.insert(item)).flatMap(saved->journal.fence(saved).flatMap(fenced->
          fenced==(target==null?1:2)?Mono.just(saved):Mono.error(changed(current))));
    }));
  }

  /** Finalizes only a validated durable intent. Membership/archive changes cannot undo an issue commit. */
  @Transactional
  public Mono<Void> complete(IssueTypeDeletion item) {
    return lock(item.projectId()).then(require(item)).flatMap(current->{
      if(current.state().equals("COMPLETED"))return Mono.empty();
      if(current.state().equals("CANCELLED"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Deletion was cancelled"));
      return types.find(current.projectId(),current.source()).filter(type->type.generation().equals(current.sourceGeneration())
              &&current.id().equals(type.pendingOperationId()))
          .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Issue type lifecycle changed while finalizing")))
          .then(current.target()==null?Mono.empty():types.find(current.projectId(),current.target())
              .filter(type->type.generation().equals(current.targetGeneration())&&current.id().equals(type.pendingOperationId()))
              .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Target issue type lifecycle changed"))).then())
          .then(journal.deleteSource(current)).flatMap(deleted->deleted==1?Mono.empty():Mono.error(
              new DomainException(DomainStatus.ABORTED,"Issue type deletion did not match its generation")))
          .then(journal.clearFence(current)).then(types.syncLegacySettings(current.projectId(),current.actor()))
          .then(journal.terminal(current,"COMPLETED")).then(operations.close(current.projectId(),current.id(),"DELETE_TYPE"));
    });
  }

  @Transactional
  public Mono<IssueTypeDeletion> requestCancellation(IssueTypeDeletion item,DomainException error) {
    return lock(item.projectId()).then(require(item)).flatMap(current->journal.requestCancellation(current,error));
  }

  /** Called only after the issue database has durably fenced the operation as CANCELLED. */
  @Transactional
  public Mono<Void> cancel(IssueTypeDeletion item) {
    return lock(item.projectId()).then(require(item)).flatMap(current->{
      if(current.state().equals("COMPLETED"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Deletion already completed"));
      return journal.clearFence(current).then(journal.terminal(current,"CANCELLED")).then(operations.close(current.projectId(),current.id(),"DELETE_TYPE"));
    });
  }

  private Mono<IssueTypeDeletion> require(IssueTypeDeletion item) {
    return journal.find(item.projectId(),item.id()).switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Deletion intent not found")));
  }
  private Mono<Void> authorize(UUID project,UUID actor) {
    return lock(project).then(types.lockActorRole(project,actor)).filter(role->role==ProjectRole.ADMIN)
        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Project ADMIN role is required")))
        .then(writeGuard.check(project,actor));
  }
  private Mono<Void> lock(UUID project) {
    return types.lockProject(project).switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found"))).then();
  }
  private void validate(String source,String target,Map<String,String> mapping,UUID expected) {
    if(source==null||!source.matches("^[A-Z][A-Z0-9_]{1,31}$"))throw new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid issue type code");
    if(target!=null&&(target.equals(source)||!target.matches("^[A-Z][A-Z0-9_]{1,31}$")))throw new DomainException(
        DomainStatus.INVALID_ARGUMENT,"Target must be a different registered project issue type",Map.of("reason","ISSUE_TYPE_TARGET_INVALID"));
    if(target==null&&(!mapping.isEmpty()||expected!=null))throw new DomainException(DomainStatus.INVALID_ARGUMENT,"moveIssuesTo is required for mapping/generation");
    if(mapping.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null
        ||!e.getKey().matches("^[A-Z][A-Z0-9_]{1,63}$")||!e.getValue().matches("^[A-Z][A-Z0-9_]{1,63}$")))
      throw new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid status mapping");
  }
  private static <T> Mono<T> missing(){return Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Issue type not found in this project"));}
  private static DomainException changed(ProjectIssueType type){return new DomainException(DomainStatus.ABORTED,"Issue type lifecycle changed",
      Map.of("reason","ISSUE_TYPE_CHANGED","generation",type.generation().toString()));}
  public static DomainException pendingError(UUID id){return new DomainException(DomainStatus.UNAVAILABLE,
      "Issue type deletion is durably pending; retry the same command",
      Map.of("reason","ISSUE_TYPE_DELETION_PENDING","operationId",id.toString()));}
}
