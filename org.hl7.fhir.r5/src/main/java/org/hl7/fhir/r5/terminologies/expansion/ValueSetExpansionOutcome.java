package org.hl7.fhir.r5.terminologies.expansion;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r5.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.terminologies.ValueSetUtilities;
import org.hl7.fhir.r5.terminologies.utilities.TerminologyServiceErrorClass;
import org.hl7.fhir.r5.terminologies.utilities.ValueSetProcessBase;
import org.hl7.fhir.utilities.MarkedToMoveToAdjunctPackage;

/**
 * Some value sets are just too big to expand. Instead of an expanded value set, 
 * you get back an interface that can test membership - usually on a server somewhere
 * 
 * @author Grahame
 */
@MarkedToMoveToAdjunctPackage
public class ValueSetExpansionOutcome {
  private String msgId;
  private ValueSetProcessBase.OpIssueCode code;
  private ValueSet valueset;
  private String error;
  private TerminologyServiceErrorClass errorClass;
  private String txLink;
  private List<String> allErrors = new ArrayList<>();
  private boolean fromServer;
  private List<OperationOutcomeIssueComponent> issues;
  
  public ValueSetExpansionOutcome(ValueSet valueset) {
    super();
    this.valueset = valueset;
    this.error = null;
  }
  public ValueSetExpansionOutcome(ValueSet valueset, String error, TerminologyServiceErrorClass errorClass, boolean fromServer) {
    super();
    this.valueset = valueset;
    this.error = error;
    this.errorClass = errorClass;
    this.fromServer = fromServer;
    allErrors.add(error);
  }

  public ValueSetExpansionOutcome(String error, TerminologyServiceErrorClass errorClass, boolean fromServer) {
    this.valueset = null;
    this.error = error;
    this.errorClass = errorClass;
    this.fromServer = fromServer;
    allErrors.add(error);
  }
  public ValueSetExpansionOutcome(String error, TerminologyServiceErrorClass errorClass, List<String> errList, boolean fromServer) {
    this.valueset = null;
    this.error = error;
    this.errorClass = errorClass;
    this.fromServer = fromServer;
    if (errList != null) {
      this.allErrors.addAll(errList);
    }
    if (!allErrors.contains(error)) {
      allErrors.add(error);
    }
    if (errList == null || !errList.contains(error)) {
      errList.add(error);
    }
  }

  public ValueSetExpansionOutcome(String error, TerminologyServiceErrorClass errorClass, List<String> errList, boolean fromServer, String msgId, ValueSetProcessBase.OpIssueCode code) {
    this.valueset = null;
    this.error = error;
    this.errorClass = errorClass;
    this.fromServer = fromServer;
    if (errList != null) {
      this.allErrors.addAll(errList);
    }
    if (!allErrors.contains(error)) {
      allErrors.add(error);
    }
    if (errList == null || !errList.contains(error)) {
      errList.add(error);
    }
    this.msgId = msgId;
    this.code = code;
  }

  public ValueSetExpansionOutcome(String error, TerminologyServiceErrorClass errorClass, List<String> errList, List<OperationOutcomeIssueComponent> issueList) {
    this.valueset = null;
    this.error = error;
    this.errorClass = errorClass;
    this.allErrors.addAll(errList);
    if (!allErrors.contains(error)) {
      allErrors.add(error);
    }
    if (!errList.contains(error)) {     
      errList.add(error);
    }
    this.issues = issueList;
  }
  
  /**
   * A deep copy, for run-scoped memoization of local expansion outcomes (BaseWorkerContext): the stored
   * and the returned outcomes must never alias, because callers mutate both the outcome's ValueSet and
   * its error/issue lists. Note that ValueSet.copy() does not (by default) carry userData - e.g.
   * UserDataNames.VS_EXPANSION_SOURCE - so callers that need userData must re-stamp it on the copy.
   */
  public ValueSetExpansionOutcome copy() {
    ValueSetExpansionOutcome that = new ValueSetExpansionOutcome(valueset == null ? null : valueset.copy());
    that.msgId = msgId;
    that.code = code;
    that.error = error;
    that.errorClass = errorClass;
    that.txLink = txLink;
    that.allErrors = new ArrayList<>(allErrors);
    that.fromServer = fromServer;
    if (issues != null) {
      that.issues = new ArrayList<>();
      for (OperationOutcomeIssueComponent iss : issues) {
        that.issues.add(iss.copy());
      }
    }
    return that;
  }

  public ValueSet getValueset() {
    return valueset;
  }
  public String getError() {
    return error;
  }
  public TerminologyServiceErrorClass getErrorClass() {
    return errorClass;
  }
  public String getTxLink() {
    return txLink;
  }
  public ValueSetExpansionOutcome setTxLink(String txLink) {
    this.txLink = txLink;
    return this;
  }
  public List<String> getAllErrors() {
    return allErrors;
  }
  
  public boolean isFromServer() {
    return fromServer;
  }
  public boolean isOk() {
    return (allErrors.isEmpty() || (allErrors.size() == 1 && allErrors.get(0) == null)) && error == null;
  }
  public int count() {
    if (valueset == null) {
      return 0; 
    }
    return ValueSetUtilities.countExpansion(valueset);
  }
  public List<OperationOutcomeIssueComponent> getIssues() {
    return issues;
  }

  public String getMsgId() {
    return msgId;
  }

  public ValueSetProcessBase.OpIssueCode getCode() {
    return code;
  }
}