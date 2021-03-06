/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.server.impl.cmp;

import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.util.Date;

import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.cmp.ErrorMsgContent;
import org.bouncycastle.asn1.cmp.PKIBody;
import org.bouncycastle.asn1.cmp.PKIFailureInfo;
import org.bouncycastle.asn1.cmp.PKIFreeText;
import org.bouncycastle.asn1.cmp.PKIHeader;
import org.bouncycastle.asn1.cmp.PKIHeaderBuilder;
import org.bouncycastle.asn1.cmp.PKIMessage;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.cmp.PKIStatusInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.cert.cmp.CMPException;
import org.bouncycastle.cert.cmp.GeneralPKIMessage;
import org.bouncycastle.cert.cmp.ProtectedPKIMessage;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.AuditEvent;
import org.xipki.audit.AuditLevel;
import org.xipki.audit.AuditStatus;
import org.xipki.ca.server.api.CaAuditConstants;
import org.xipki.ca.server.mgmt.api.CmpControl;
import org.xipki.ca.server.mgmt.api.RequestorInfo;
import org.xipki.cmp.CmpUtil;
import org.xipki.cmp.ProtectionResult;
import org.xipki.cmp.ProtectionVerificationResult;
import org.xipki.common.util.Base64;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.RandomUtil;
import org.xipki.security.ConcurrentContentSigner;
import org.xipki.security.SecurityFactory;
import org.xipki.security.util.X509Util;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

abstract class CmpResponder {

  private static final Logger LOG = LoggerFactory.getLogger(CmpResponder.class);

  private static final int PVNO_CMP2000 = 2;

  protected final SecurityFactory securityFactory;

  private final SecureRandom random = new SecureRandom();

  protected CmpResponder(SecurityFactory securityFactory) {
    this.securityFactory = ParamUtil.requireNonNull("securityFactory", securityFactory);
  }

  protected abstract ConcurrentContentSigner getSigner();

  protected abstract GeneralName getSender();

  protected abstract boolean intendsMe(GeneralName requestRecipient);

  public boolean isOnService() {
    try {
      return getSigner() != null;
    } catch (Exception ex) {
      LogUtil.error(LOG, ex, "could not get responder signer");
      return false;
    }
  }

  /**
   * TODO.
   * @return never returns {@code null}.
   */
  protected abstract CmpControl getCmpControl();

  public abstract CmpRequestorInfo getRequestor(X500Name requestorSender);

  public abstract CmpRequestorInfo getRequestor(X509Certificate requestorCert);

  private CmpRequestorInfo getRequestor(PKIHeader reqHeader) {
    GeneralName requestSender = reqHeader.getSender();
    if (requestSender.getTagNo() != GeneralName.directoryName) {
      return null;
    }

    return getRequestor((X500Name) requestSender.getName());
  } // method getRequestor

  /**
   * Processes the request and returns the response.
   * @param request
   *          Original request. Will only be used for the storage. Could be{@code null}.
   * @param requestor
   *          Requestor. Must not be {@code null}.
   * @param transactionId
   *          Transaction id. Must not be {@code null}.
   * @param pkiMessage
   *          PKI message. Must not be {@code null}.
   * @param msgId
   *          Message id. Must not be {@code null}.
   * @param event
   *          Audit event. Must not be {@code null}.
   * @return the response
   */
  protected abstract PKIMessage processPkiMessage0(PKIMessage request, RequestorInfo requestor,
      ASN1OctetString transactionId, GeneralPKIMessage pkiMessage, String msgId,
      AuditEvent event);

  public PKIMessage processPkiMessage(PKIMessage pkiMessage, X509Certificate tlsClientCert,
      AuditEvent event) {
    ParamUtil.requireNonNull("pkiMessage", pkiMessage);
    ParamUtil.requireNonNull("event", event);
    GeneralPKIMessage message = new GeneralPKIMessage(pkiMessage);

    PKIHeader reqHeader = message.getHeader();
    ASN1OctetString tid = reqHeader.getTransactionID();

    String msgId = null;
    if (event != null) {
      msgId = RandomUtil.nextHexLong();
      event.addEventData(CaAuditConstants.NAME_mid, msgId);
    }

    if (tid == null) {
      byte[] randomBytes = randomTransactionId();
      tid = new DEROctetString(randomBytes);
    }
    String tidStr = Base64.encodeToString(tid.getOctets());
    if (event != null) {
      event.addEventData(CaAuditConstants.NAME_tid, tidStr);
    }

    int reqPvno = reqHeader.getPvno().getValue().intValue();
    if (reqPvno != PVNO_CMP2000) {
      if (event != null) {
        event.setLevel(AuditLevel.INFO);
        event.setStatus(AuditStatus.FAILED);
        event.addEventData(CaAuditConstants.NAME_message, "unsupproted version " + reqPvno);
      }
      return buildErrorPkiMessage(tid, reqHeader, PKIFailureInfo.unsupportedVersion, null);
    }

    CmpControl cmpControl = getCmpControl();

    Integer failureCode = null;
    String statusText = null;

    Date messageTime = null;
    if (reqHeader.getMessageTime() != null) {
      try {
        messageTime = reqHeader.getMessageTime().getDate();
      } catch (ParseException ex) {
        LogUtil.error(LOG, ex, "tid=" + tidStr + ": could not parse messageTime");
      }
    }

    GeneralName recipient = reqHeader.getRecipient();
    boolean intentMe = (recipient == null) ? true : intendsMe(recipient);
    if (!intentMe) {
      LOG.warn("tid={}: I am not the intended recipient, but '{}'", tid,
          reqHeader.getRecipient());
      failureCode = PKIFailureInfo.badRequest;
      statusText = "I am not the intended recipient";
    } else if (messageTime == null) {
      if (cmpControl.isMessageTimeRequired()) {
        failureCode = PKIFailureInfo.missingTimeStamp;
        statusText = "missing time-stamp";
      }
    } else {
      long messageTimeBias = cmpControl.getMessageTimeBias();
      if (messageTimeBias < 0) {
        messageTimeBias *= -1;
      }

      long msgTimeMs = messageTime.getTime();
      long currentTimeMs = System.currentTimeMillis();
      long bias = (msgTimeMs - currentTimeMs) / 1000L;
      if (bias > messageTimeBias) {
        failureCode = PKIFailureInfo.badTime;
        statusText = "message time is in the future";
      } else if (bias * -1 > messageTimeBias) {
        failureCode = PKIFailureInfo.badTime;
        statusText = "message too old";
      }
    }

    if (failureCode != null) {
      if (event != null) {
        event.setLevel(AuditLevel.INFO);
        event.setStatus(AuditStatus.FAILED);
        event.addEventData(CaAuditConstants.NAME_message, statusText);
      }
      return buildErrorPkiMessage(tid, reqHeader, failureCode, statusText);
    }

    boolean isProtected = message.hasProtection();
    CmpRequestorInfo requestor;

    String errorStatus;

    if (isProtected) {
      try {
        ProtectionVerificationResult verificationResult = verifyProtection(tidStr,
            message, cmpControl);
        ProtectionResult pr = verificationResult.getProtectionResult();
        switch (pr) {
          case VALID:
            errorStatus = null;
            break;
          case INVALID:
            errorStatus = "request is protected by signature but invalid";
            break;
          case NOT_SIGNATURE_BASED:
            errorStatus = "request is not protected by signature";
            break;
          case SENDER_NOT_AUTHORIZED:
            errorStatus = "request is protected by signature but the requestor is not authorized";
            break;
          case SIGALGO_FORBIDDEN:
            errorStatus = "request is protected by signature but the protection algorithm"
              + " is forbidden";
            break;
          default:
            throw new RuntimeException("should not reach here, unknown ProtectionResult " + pr);
        } // end switch
        requestor = (CmpRequestorInfo) verificationResult.getRequestor();
      } catch (Exception ex) {
        LogUtil.error(LOG, ex, "tid=" + tidStr + ": could not verify the signature");
        errorStatus = "request has invalid signature based protection";
        requestor = null;
      }
    } else if (tlsClientCert != null) {
      boolean authorized = false;

      requestor = getRequestor(reqHeader);
      if (requestor != null) {
        if (tlsClientCert.equals(requestor.getCert().getCert())) {
          authorized = true;
        }
      }

      if (authorized) {
        errorStatus = null;
      } else {
        LOG.warn("tid={}: not authorized requestor (TLS client '{}')", tid,
            X509Util.getRfc4519Name(tlsClientCert.getSubjectX500Principal()));
        errorStatus = "requestor (TLS client certificate) is not authorized";
      }
    } else {
      errorStatus = "request has no protection";
      requestor = null;
    }

    if (errorStatus != null) {
      if (event != null) {
        event.setLevel(AuditLevel.INFO);
        event.setStatus(AuditStatus.FAILED);
        event.addEventData(CaAuditConstants.NAME_message, errorStatus);
      }
      return buildErrorPkiMessage(tid, reqHeader, PKIFailureInfo.badMessageCheck,
          errorStatus);
    }

    PKIMessage resp = processPkiMessage0(pkiMessage, requestor, tid, message, msgId, event);

    if (isProtected) {
      resp = addProtection(resp, event);
    } else {
      // protected by TLS connection
    }

    return resp;
  } // method processPkiMessage

  protected byte[] randomTransactionId() {
    byte[] bytes = new byte[10];
    random.nextBytes(bytes);
    return bytes;
  }

  private ProtectionVerificationResult verifyProtection(String tid, GeneralPKIMessage pkiMessage,
      CmpControl cmpControl)
      throws CMPException, InvalidKeyException, OperatorCreationException {
    ProtectedPKIMessage protectedMsg = new ProtectedPKIMessage(pkiMessage);

    if (protectedMsg.hasPasswordBasedMacProtection()) {
      LOG.warn("NOT_SIGNAUTRE_BASED: {}",
          pkiMessage.getHeader().getProtectionAlg().getAlgorithm().getId());
      return new ProtectionVerificationResult(null, ProtectionResult.NOT_SIGNATURE_BASED);
    }

    PKIHeader header = protectedMsg.getHeader();
    AlgorithmIdentifier protectionAlg = header.getProtectionAlg();
    if (!cmpControl.getSigAlgoValidator().isAlgorithmPermitted(protectionAlg)) {
      LOG.warn("SIG_ALGO_FORBIDDEN: {}",
          pkiMessage.getHeader().getProtectionAlg().getAlgorithm().getId());
      return new ProtectionVerificationResult(null, ProtectionResult.SIGALGO_FORBIDDEN);
    }

    CmpRequestorInfo requestor = getRequestor(header);
    if (requestor == null) {
      LOG.warn("tid={}: not authorized requestor '{}'", tid, header.getSender());
      return new ProtectionVerificationResult(null, ProtectionResult.SENDER_NOT_AUTHORIZED);
    }

    ContentVerifierProvider verifierProvider = securityFactory.getContentVerifierProvider(
        requestor.getCert().getCert());
    if (verifierProvider == null) {
      LOG.warn("tid={}: not authorized requestor '{}'", tid, header.getSender());
      return new ProtectionVerificationResult(requestor,
          ProtectionResult.SENDER_NOT_AUTHORIZED);
    }

    boolean signatureValid = protectedMsg.verify(verifierProvider);
    return new ProtectionVerificationResult(requestor,
        signatureValid ? ProtectionResult.VALID : ProtectionResult.INVALID);
  } // method verifyProtection

  private PKIMessage addProtection(PKIMessage pkiMessage, AuditEvent event) {
    try {
      return CmpUtil.addProtection(pkiMessage, getSigner(), getSender(),
          getCmpControl().isSendResponderCert());
    } catch (Exception ex) {
      LogUtil.error(LOG, ex, "could not add protection to the PKI message");
      PKIStatusInfo status = generateRejectionStatus(
          PKIFailureInfo.systemFailure, "could not sign the PKIMessage");

      event.setLevel(AuditLevel.ERROR);
      event.setStatus(AuditStatus.FAILED);
      event.addEventData(CaAuditConstants.NAME_message, "could not sign the PKIMessage");
      PKIBody body = new PKIBody(PKIBody.TYPE_ERROR, new ErrorMsgContent(status));
      return new PKIMessage(pkiMessage.getHeader(), body);
    }
  } // method addProtection

  protected PKIMessage buildErrorPkiMessage(ASN1OctetString tid,
      PKIHeader requestHeader, int failureCode, String statusText) {
    GeneralName respRecipient = requestHeader.getSender();

    PKIHeaderBuilder respHeader = new PKIHeaderBuilder(
        requestHeader.getPvno().getValue().intValue(), getSender(), respRecipient);
    respHeader.setMessageTime(new ASN1GeneralizedTime(new Date()));
    if (tid != null) {
      respHeader.setTransactionID(tid);
    }

    ASN1OctetString senderNonce = requestHeader.getSenderNonce();
    if (senderNonce != null) {
      respHeader.setRecipNonce(senderNonce);
    }

    PKIStatusInfo status = generateRejectionStatus(failureCode, statusText);
    ErrorMsgContent error = new ErrorMsgContent(status);
    PKIBody body = new PKIBody(PKIBody.TYPE_ERROR, error);

    return new PKIMessage(respHeader.build(), body);
  } // method buildErrorPkiMessage

  protected PKIStatusInfo generateRejectionStatus(Integer info, String errorMessage) {
    return generateRejectionStatus(PKIStatus.rejection, info, errorMessage);
  } // method generateCmpRejectionStatus

  protected PKIStatusInfo generateRejectionStatus(PKIStatus status, Integer info,
      String errorMessage) {
    PKIFreeText statusMessage = (errorMessage == null) ? null : new PKIFreeText(errorMessage);
    PKIFailureInfo failureInfo = (info == null) ? null : new PKIFailureInfo(info);
    return new PKIStatusInfo(status, statusMessage, failureInfo);
  } // method generateCmpRejectionStatus

  public X500Name getResponderSubject() {
    GeneralName sender = getSender();
    return (sender == null) ? null : (X500Name) sender.getName();
  }

  public X509Certificate getResponderCert() {
    ConcurrentContentSigner signer = getSigner();
    return (signer == null) ? null : signer.getCertificate();
  }

}
