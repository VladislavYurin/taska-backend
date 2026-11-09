package ru.taska.workflowstorage;

import java.util.*;
import tools.jackson.databind.JsonNode;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Published schema v1 uses stable keys, never tenant/runtime UUID references. */
record TemplateGraph(List<Node> statuses, List<Edge> transitions) {
  record Node(String key, String name, String category, int sortOrder) {}
  record Edge(String from, String to, String name, boolean hidden, int sortOrder, List<Rule> rules) {}
  record Rule(String type, JsonNode config, String errorCode, String errorMessage, int sortOrder) {}

  TemplateGraph validated() {
    if (statuses==null || statuses.isEmpty() || transitions==null) throw invalid();
    var keys=new HashSet<String>();
    for(var node:statuses) {
      if(node==null || node.key()==null || !node.key().matches("^[A-Z][A-Z0-9_]{1,63}$")
          || !keys.add(node.key()) || node.name()==null || node.name().isBlank()
          || node.category()==null || !Set.of("TODO","IN_PROGRESS","DONE").contains(node.category())
          || node.sortOrder()<0) throw invalid();
    }
    var pairs=new HashSet<List<String>>();
    for(var edge:transitions) {
      if(edge==null || !keys.contains(edge.from()) || !keys.contains(edge.to()) || edge.from().equals(edge.to())
          || !pairs.add(List.of(edge.from(),edge.to())) || edge.name()==null || edge.name().isBlank()
          || edge.sortOrder()<0 || edge.rules()==null) throw invalid();
      var rules=new HashSet<String>();
      for(var rule:edge.rules()) if(rule==null || rule.type()==null
          || !Set.of("REQUIRE_FIELD","REQUIRE_ASSIGNEE","REQUIRE_COMMENT").contains(rule.type())
          || rule.config()==null || !rule.config().isObject() || rule.sortOrder()<0
          || !rules.add(rule.type()+":"+rule.sortOrder())) throw invalid();
    }
    return this;
  }

  static DomainException invalid() {
    return new DomainException(DomainStatus.FAILED_PRECONDITION,"Invalid or unsupported published workflow template");
  }
}
