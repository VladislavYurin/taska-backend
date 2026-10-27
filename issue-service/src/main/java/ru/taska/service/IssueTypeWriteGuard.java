package ru.taska.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;

/** Ambient issue transaction: project lock -> fresh registry validation -> issue row locks/writes. */
@Component
@RequiredArgsConstructor
public class IssueTypeWriteGuard {
  private final DatabaseClient db;
  private final GrpcProjectServiceClient projects;
  public Mono<Void> lock(UUID project) {
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:project,433))")
        .bind("project",project.toString()).map((r,m)->1).one().then();
  }
  public Mono<Void> requireActive(String request,String node,UUID project,UUID actor,Collection<IssueType> requested) {
    return requireActive(request,node,project,actor,requested,List.of());
  }
  /** Old orphaned source types may be repaired; a registered source still obeys its pending fence. */
  public Mono<Void> requireTypeChange(String request,String node,UUID project,UUID actor,IssueType source,IssueType target) {
    return requireActive(request,node,project,actor,List.of(target),List.of(source));
  }
  private Mono<Void> requireActive(String request,String node,UUID project,UUID actor,Collection<IssueType> required,Collection<IssueType> optional) {
    return projects.listIssueTypeDetails(request,node,project,actor).flatMap(types-> {
      var requested=new java.util.ArrayList<>(required);
      optional.stream().filter(type->types.stream().anyMatch(t->t.getCode().equals(type.name()))).forEach(requested::add);
      return
        Flux.fromIterable(requested.stream().distinct().toList()).concatMap(type->{
          var found=types.stream().filter(t->t.getCode().equals(type.name())).findFirst();
          if(found.isEmpty())return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
              "Issue type "+type+" is not defined in this project"));
          var current=found.get();
          if(!current.getPendingOperationId().isBlank())return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
              "Issue type deletion is pending",Map.of("reason","ISSUE_TYPE_DELETION_PENDING")));
          UUID generation;
          try {generation=UUID.fromString(current.getGeneration());}
          catch(IllegalArgumentException unsupported){return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,
              "Project service must support generation-aware issue writes"));}
          return db.sql("SELECT EXISTS(SELECT 1 FROM taska.issue_type_move_receipts WHERE project_id=:project AND source_code=:code AND source_generation=:generation AND state='APPLIED') AS retired")
              .bind("project",project).bind("code",type.name()).bind("generation",generation)
              .map((r,m)->Boolean.TRUE.equals(r.get("retired",Boolean.class))).one()
              .flatMap(retired->retired?Mono.error(new DomainException(DomainStatus.ABORTED,"Issue type lifecycle changed",
                  Map.of("reason","ISSUE_TYPE_CHANGED"))):Mono.empty());
        }).then();
    });
  }
}
