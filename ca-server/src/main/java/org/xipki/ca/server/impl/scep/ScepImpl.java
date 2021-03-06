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

package org.xipki.ca.server.impl.scep;

import java.io.IOException;
import java.math.BigInteger;
import java.security.Key;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.bouncycastle.asn1.pkcs.CertificationRequestInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.CertificateList;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSAbsentContent;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.AuditEvent;
import org.xipki.audit.AuditStatus;
import org.xipki.ca.api.NameId;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.OperationException.ErrorCode;
import org.xipki.ca.api.RequestType;
import org.xipki.ca.api.publisher.x509.X509CertificateInfo;
import org.xipki.ca.server.api.CaAuditConstants;
import org.xipki.ca.server.api.Scep;
import org.xipki.ca.server.api.ScepCaCertRespBytes;
import org.xipki.ca.server.impl.ByUserRequestorInfo;
import org.xipki.ca.server.impl.CaManagerImpl;
import org.xipki.ca.server.impl.CertTemplateData;
import org.xipki.ca.server.impl.KnowCertResult;
import org.xipki.ca.server.impl.X509Ca;
import org.xipki.ca.server.impl.cmp.ResponderEntryWrapper;
import org.xipki.ca.server.impl.util.CaUtil;
import org.xipki.ca.server.mgmt.api.CaMgmtException;
import org.xipki.ca.server.mgmt.api.CaStatus;
import org.xipki.ca.server.mgmt.api.PermissionConstants;
import org.xipki.ca.server.mgmt.api.x509.ScepControl;
import org.xipki.ca.server.mgmt.api.x509.ScepEntry;
import org.xipki.common.InvalidConfException;
import org.xipki.common.util.Base64;
import org.xipki.common.util.CollectionUtil;
import org.xipki.common.util.Hex;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.scep.crypto.ScepHashAlgo;
import org.xipki.scep.exception.MessageDecodingException;
import org.xipki.scep.exception.MessageEncodingException;
import org.xipki.scep.message.CaCaps;
import org.xipki.scep.message.DecodedPkiMessage;
import org.xipki.scep.message.EnvelopedDataDecryptor;
import org.xipki.scep.message.EnvelopedDataDecryptorInstance;
import org.xipki.scep.message.IssuerAndSubject;
import org.xipki.scep.message.PkiMessage;
import org.xipki.scep.transaction.CaCapability;
import org.xipki.scep.transaction.FailInfo;
import org.xipki.scep.transaction.MessageType;
import org.xipki.scep.transaction.Nonce;
import org.xipki.scep.transaction.PkiStatus;
import org.xipki.scep.transaction.TransactionId;
import org.xipki.security.ConcurrentContentSigner;
import org.xipki.security.X509Cert;
import org.xipki.security.util.X509Util;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 *
 */
public class ScepImpl implements Scep {

  private static final Logger LOG = LoggerFactory.getLogger(ScepImpl.class);

  private static final long DFLT_MAX_SIGNINGTIME_BIAS = 5L * 60 * 1000; // 5 minutes

  private static final Set<ASN1ObjectIdentifier> AES_ENC_ALGOS = new HashSet<>();

  private final String name;

  private final NameId caIdent;

  private final ScepEntry dbEntry;

  private final Set<String> certProfiles;

  private final ScepControl control;

  private final CaManagerImpl caManager;

  private final PrivateKey responderKey;

  private final X509Certificate responderCert;

  private final CaCaps caCaps;

  private final EnvelopedDataDecryptor envelopedDataDecryptor;

  private X509Cert caCert;

  private ScepCaCertRespBytes caCertRespBytes;

  private long maxSigningTimeBiasInMs = DFLT_MAX_SIGNINGTIME_BIAS;

  static {
    AES_ENC_ALGOS.add(CMSAlgorithm.AES128_CBC);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES128_CCM);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES128_GCM);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES192_CBC);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES192_CCM);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES192_GCM);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES256_CBC);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES256_CCM);
    AES_ENC_ALGOS.add(CMSAlgorithm.AES256_GCM);
  }

  public ScepImpl(ScepEntry dbEntry, CaManagerImpl caManager) throws CaMgmtException {
    this.caManager = ParamUtil.requireNonNull("caManager", caManager);
    this.dbEntry = ParamUtil.requireNonNull("dbEntry", dbEntry);
    this.name = dbEntry.getName();
    this.caIdent = dbEntry.getCaIdent();
    this.certProfiles = dbEntry.getCertProfiles();
    try {
      this.control = new ScepControl(dbEntry.getControl());
    } catch (InvalidConfException ex) {
      throw new CaMgmtException(ex);
    }
    LOG.info("SCEP {}: cacert.included={}, signercert.included={},support.getcrl={}",
        this.caIdent, this.control.isIncludeCaCert(), this.control.isIncludeSignerCert(),
        this.control.isSupportGetCrl());

    String responderName = dbEntry.getResponderName();
    ResponderEntryWrapper responder = caManager.getResponderWrapper(responderName);
    if (responder == null) {
      throw new CaMgmtException("Unknown responder " + responderName);
    }

    ConcurrentContentSigner signer = responder.getSigner();

    Key signingKey = signer.getSigningKey();
    if (!(signingKey instanceof PrivateKey)) {
      throw new CaMgmtException("Unsupported signer type: the signing key is not a PrivateKey");
    }
    this.responderKey = (PrivateKey) signingKey;
    this.responderCert = signer.getCertificate();

    if (!(responderCert.getPublicKey() instanceof RSAPublicKey)) {
      throw new IllegalArgumentException("The responder key is not RSA key for SCEP " + name);
    }

    // CACaps
    CaCaps caps = new CaCaps();
    caps.addCapabilities(CaCapability.AES, CaCapability.DES3, CaCapability.POSTPKIOperation,
        CaCapability.Renewal, CaCapability.SHA1, CaCapability.SHA256, CaCapability.SHA512);
    this.caCaps = caps;

    EnvelopedDataDecryptorInstance di = new EnvelopedDataDecryptorInstance(responderCert,
        responderKey);
    this.envelopedDataDecryptor = new EnvelopedDataDecryptor(di);

  }

  /**
   * TODO.
   * @param ms signing time bias in milliseconds. non-positive value deactivate the check of
   *     signing time.
   */
  public void setMaxSigningTimeBias(long ms) {
    this.maxSigningTimeBiasInMs = ms;
  }

  public String getName() {
    return name;
  }

  public NameId getCaIdent() {
    return caIdent;
  }

  public ScepEntry getDbEntry() {
    return dbEntry;
  }

  public CaCaps getCaCaps() {
    return caCaps;
  }

  public ScepCaCertRespBytes getCaCertResp() throws OperationException {
    refreshCa();
    return caCertRespBytes;
  }

  public boolean supportsCertProfile(String profileName) {
    if (certProfiles.contains("all") || certProfiles.contains(profileName.toLowerCase())) {
      X509Ca ca;
      try {
        ca = caManager.getX509Ca(caIdent);
      } catch (CaMgmtException ex) {
        LogUtil.warn(LOG, ex);
        return false;
      }
      return ca.supportsCertProfile(profileName);
    } else {
      return false;
    }
  }

  @Override
  public boolean isOnService() {
    return getStatus() == CaStatus.ACTIVE;
  }

  public CaStatus getStatus() {
    if (!dbEntry.isActive()) {
      return CaStatus.INACTIVE;
    }

    try {
      return caManager.getX509Ca(caIdent).getCaInfo().getStatus();
    } catch (CaMgmtException ex) {
      LogUtil.error(LOG, ex);
      return CaStatus.INACTIVE;
    }
  }

  public ContentInfo servicePkiOperation(CMSSignedData requestContent, String certProfileName,
      String msgId, AuditEvent event) throws MessageDecodingException, OperationException {
    CaStatus status = getStatus();

    if (CaStatus.ACTIVE != status) {
      LOG.warn("SCEP {} is not active", caIdent);
      throw new OperationException(ErrorCode.SYSTEM_UNAVAILABLE);
    }

    DecodedPkiMessage req = DecodedPkiMessage.decode(requestContent, envelopedDataDecryptor, null);

    PkiMessage rep = servicePkiOperation0(requestContent, req, certProfileName, msgId, event);
    audit(event, CaAuditConstants.NAME_SCEP_pkiStatus, rep.getPkiStatus().toString());
    if (rep.getPkiStatus() == PkiStatus.FAILURE) {
      event.setStatus(AuditStatus.FAILED);
    }
    if (rep.getFailInfo() != null) {
      audit(event, CaAuditConstants.NAME_SCEP_failInfo, rep.getFailInfo().toString());
    }
    return encodeResponse(rep, req);
  } // method servicePkiOperation

  private PkiMessage servicePkiOperation0(CMSSignedData requestContent,
      DecodedPkiMessage req, String certProfileName, String msgId, AuditEvent event)
      throws MessageDecodingException, OperationException {
    ParamUtil.requireNonNull("requestContent", requestContent);
    ParamUtil.requireNonNull("req", req);

    String tid = req.getTransactionId().getId();
    // verify and decrypt the request
    audit(event, CaAuditConstants.NAME_tid, tid);
    if (req.getFailureMessage() != null) {
      audit(event, CaAuditConstants.NAME_SCEP_failureMessage, req.getFailureMessage());
    }
    Boolean bo = req.isSignatureValid();
    if (bo != null && !bo.booleanValue()) {
      audit(event, CaAuditConstants.NAME_SCEP_signature, "invalid");
    }
    bo = req.isDecryptionSuccessful();
    if (bo != null && !bo.booleanValue()) {
      audit(event, CaAuditConstants.NAME_SCEP_decryption, "failed");
    }

    PkiMessage rep =
        new PkiMessage(req.getTransactionId(), MessageType.CertRep, Nonce.randomNonce());
    rep.setRecipientNonce(req.getSenderNonce());

    if (req.getFailureMessage() != null) {
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(FailInfo.badRequest);
      return rep;
    }

    bo = req.isSignatureValid();
    if (bo != null && !bo.booleanValue()) {
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(FailInfo.badMessageCheck);
      return rep;
    }

    bo = req.isDecryptionSuccessful();
    if (bo != null && !bo.booleanValue()) {
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(FailInfo.badRequest);
      return rep;
    }

    Date signingTime = req.getSigningTime();
    if (maxSigningTimeBiasInMs > 0) {
      boolean isTimeBad = false;
      if (signingTime == null) {
        isTimeBad = true;
      } else {
        long now = System.currentTimeMillis();
        long diff = now - signingTime.getTime();
        if (diff < 0) {
          diff = -1 * diff;
        }
        isTimeBad = diff > maxSigningTimeBiasInMs;
      }

      if (isTimeBad) {
        rep.setPkiStatus(PkiStatus.FAILURE);
        rep.setFailInfo(FailInfo.badTime);
        return rep;
      }
    } // end if

    // check the digest algorithm
    String oid = req.getDigestAlgorithm().getId();
    ScepHashAlgo hashAlgo = ScepHashAlgo.forNameOrOid(oid);
    if (hashAlgo == null) {
      LOG.warn("tid={}: unknown digest algorithm {}", tid, oid);
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(FailInfo.badAlg);
      return rep;
    }

    boolean supported = false;
    if (hashAlgo == ScepHashAlgo.SHA1) {
      if (caCaps.containsCapability(CaCapability.SHA1)) {
        supported = true;
      }
    } else if (hashAlgo == ScepHashAlgo.SHA256) {
      if (caCaps.containsCapability(CaCapability.SHA256)) {
        supported = true;
      }
    } else if (hashAlgo == ScepHashAlgo.SHA512) {
      if (caCaps.containsCapability(CaCapability.SHA512)) {
        supported = true;
      }
    }

    if (!supported) {
      LOG.warn("tid={}: unsupported digest algorithm {}", tid, oid);
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(FailInfo.badAlg);
      return rep;
    }

    // check the content encryption algorithm
    ASN1ObjectIdentifier encOid = req.getContentEncryptionAlgorithm();
    if (CMSAlgorithm.DES_EDE3_CBC.equals(encOid)) {
      if (!caCaps.containsCapability(CaCapability.DES3)) {
        LOG.warn("tid={}: encryption with DES3 algorithm is not permitted", tid, encOid);
        rep.setPkiStatus(PkiStatus.FAILURE);
        rep.setFailInfo(FailInfo.badAlg);
        return rep;
      }
    } else if (AES_ENC_ALGOS.contains(encOid)) {
      if (!caCaps.containsCapability(CaCapability.AES)) {
        LOG.warn("tid={}: encryption with AES algorithm {} is not permitted", tid, encOid);
        rep.setPkiStatus(PkiStatus.FAILURE);
        rep.setFailInfo(FailInfo.badAlg);
        return rep;
      }
    } else {
      LOG.warn("tid={}: encryption with algorithm {} is not permitted", tid, encOid);
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(FailInfo.badAlg);
      return rep;
    }

    X509Ca ca;
    try {
      ca = caManager.getX509Ca(caIdent);
    } catch (CaMgmtException ex) {
      LogUtil.error(LOG, ex, tid + "=" + tid + ",could not get X509CA");
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }

    X500Name caX500Name = ca.getCaInfo().getCert().getSubjectAsX500Name();

    try {
      SignedData signedData;

      MessageType mt = req.getMessageType();
      audit(event, CaAuditConstants.NAME_SCEP_messageType, mt.toString());

      switch (mt) {
        case PKCSReq:
        case RenewalReq:
        case UpdateReq:
          CertificationRequest csr = CertificationRequest.getInstance(req.getMessageData());
          X500Name reqSubject = csr.getCertificationRequestInfo().getSubject();
          if (LOG.isInfoEnabled()) {
            LOG.info("tid={}, subject={}", tid, X509Util.getRfc4519Name(reqSubject));
          }

          try {
            ca.checkCsr(csr);
          } catch (OperationException ex) {
            LogUtil.warn(LOG, ex, "tid=" + tid + " POPO verification failed");
            throw FailInfoException.BAD_MESSAGE_CHECK;
          }

          CertificationRequestInfo csrReqInfo = csr.getCertificationRequestInfo();
          X509Certificate reqSignatureCert = req.getSignatureCert();
          X500Principal reqSigCertSubject = reqSignatureCert.getSubjectX500Principal();

          boolean selfSigned = reqSigCertSubject.equals(reqSignatureCert.getIssuerX500Principal());
          if (selfSigned) {
            X500Name tmp = X500Name.getInstance(reqSigCertSubject.getEncoded());
            if (!tmp.equals(csrReqInfo.getSubject())) {
              LOG.warn("tid={}, self-signed identityCert.subject != csr.subject");
              throw FailInfoException.BAD_REQUEST;
            }
          }

          if (X509Util.getCommonName(csrReqInfo.getSubject()) == null) {
            throw new OperationException(ErrorCode.BAD_CERT_TEMPLATE,
                "tid=" + tid + ": no CommonName in requested subject");
          }

          NameId userIdent = null;

          String challengePwd = CaUtil.getChallengePassword(csrReqInfo);
          if (challengePwd != null) {
            String[] strs = challengePwd.split(":");
            if (strs == null || strs.length != 2) {
              LOG.warn("tid={}: challengePassword does not have the format <user>:<password>", tid);
              throw FailInfoException.BAD_REQUEST;
            }

            String user = strs[0];
            String password = strs[1];
            userIdent = ca.authenticateUser(user, password.getBytes());
            if (userIdent == null) {
              LOG.warn("tid={}: could not authenticate user {}", tid, user);
              throw FailInfoException.BAD_REQUEST;
            }
          } // end if

          if (selfSigned) {
            if (MessageType.PKCSReq != mt) {
              LOG.warn("tid={}: self-signed certificate is not permitted for"
                  + " messageType {}", tid, mt);
              throw FailInfoException.BAD_REQUEST;
            }
            if (userIdent == null) {
              LOG.warn("tid={}: could not extract user & password from challengePassword"
                  + ", which are required for self-signed signature certificate", tid);
              throw FailInfoException.BAD_REQUEST;
            }
          } else {
            // No challengePassword is sent, try to find out whether the signature
            // certificate is known by the CA
            if (userIdent == null) {
              // up to draft-nourse-scep-23 the client sends all messages to enroll
              // certificate via MessageType PKCSReq
              KnowCertResult knowCertRes = ca.knowsCertificate(reqSignatureCert);
              if (!knowCertRes.isKnown()) {
                LOG.warn("tid={}: signature certificate is not trusted by the CA", tid);
                throw FailInfoException.BAD_REQUEST;
              }

              Integer userId = knowCertRes.getUserId();
              if (userId == null) {
                LOG.warn("tid={}: could not extract user from the signature cert", tid);
                throw FailInfoException.BAD_REQUEST;
              }

              userIdent = ca.getUserIdent(userId);
            } // end if
          } // end if

          ByUserRequestorInfo requestor = ca.getByUserRequestor(userIdent);
          checkUserPermission(requestor, certProfileName);

          byte[] tidBytes = getTransactionIdBytes(tid);

          Extensions extensions = CaUtil.getExtensions(csrReqInfo);
          CertTemplateData certTemplateData = new CertTemplateData(csrReqInfo.getSubject(),
              csrReqInfo.getSubjectPublicKeyInfo(), (Date) null, (Date) null, extensions,
              certProfileName);
          X509CertificateInfo cert = ca.generateCertificate(certTemplateData, requestor,
              RequestType.SCEP, tidBytes, msgId);
          /* Don't save SCEP message, since it contains password in plaintext
          if (ca.getCaInfo().isSaveRequest() && cert.getCert().getCertId() != null) {
            byte[] encodedRequest;
            try {
              encodedRequest = requestContent.getEncoded();
            } catch (IOException ex) {
              LOG.warn("could not encode request");
              encodedRequest = null;
            }
            if (encodedRequest != null) {
              long reqId = ca.addRequest(encodedRequest);
              ca.addRequestCert(reqId, cert.getCert().getCertId());
            }
          }*/

          signedData = buildSignedData(cert.getCert().getCert());
          break;
        case CertPoll:
          IssuerAndSubject is = IssuerAndSubject.getInstance(req.getMessageData());
          audit(event, CaAuditConstants.NAME_issuer, X509Util.getRfc4519Name(is.getIssuer()));
          audit(event, CaAuditConstants.NAME_subject, X509Util.getRfc4519Name(is.getSubject()));

          ensureIssuedByThisCa(caX500Name, is.getIssuer());
          signedData = pollCert(ca, is.getSubject(), req.getTransactionId());
          break;
        case GetCert:
          IssuerAndSerialNumber isn = IssuerAndSerialNumber.getInstance(req.getMessageData());
          BigInteger serial = isn.getSerialNumber().getPositiveValue();
          audit(event, CaAuditConstants.NAME_issuer, X509Util.getRfc4519Name(isn.getName()));
          audit(event, CaAuditConstants.NAME_serial, LogUtil.formatCsn(serial));
          ensureIssuedByThisCa(caX500Name, isn.getName());
          signedData = getCert(ca, isn.getSerialNumber().getPositiveValue());
          break;
        case GetCRL:
          isn = IssuerAndSerialNumber.getInstance(req.getMessageData());
          serial = isn.getSerialNumber().getPositiveValue();
          audit(event, CaAuditConstants.NAME_issuer, X509Util.getRfc4519Name(isn.getName()));
          audit(event, CaAuditConstants.NAME_serial, LogUtil.formatCsn(serial));
          ensureIssuedByThisCa(caX500Name, isn.getName());
          signedData = getCrl(ca, serial);
          break;
        default:
          LOG.error("unknown SCEP messageType '{}'", req.getMessageType());
          throw FailInfoException.BAD_REQUEST;
      } // end switch<

      ContentInfo ci = new ContentInfo(CMSObjectIdentifiers.signedData, signedData);
      rep.setMessageData(ci);
      rep.setPkiStatus(PkiStatus.SUCCESS);
    } catch (FailInfoException ex) {
      LogUtil.error(LOG, ex);
      rep.setPkiStatus(PkiStatus.FAILURE);
      rep.setFailInfo(ex.getFailInfo());
    }

    return rep;
  } // method servicePkiOperation0

  private SignedData getCert(X509Ca ca, BigInteger serialNumber)
      throws FailInfoException, OperationException {
    X509Certificate cert;
    try {
      cert = ca.getCertificate(serialNumber);
    } catch (CertificateException ex) {
      final String message = "could not get certificate for CA '" + caIdent
          + "' and serialNumber=" + LogUtil.formatCsn(serialNumber) + ")";
      LogUtil.error(LOG, ex, message);
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }
    if (cert == null) {
      throw FailInfoException.BAD_CERTID;
    }
    return buildSignedData(cert);
  } // method getCert

  private SignedData pollCert(X509Ca ca, X500Name subject, TransactionId tid)
      throws FailInfoException, OperationException {
    byte[] tidBytes = getTransactionIdBytes(tid.getId());
    List<X509Certificate> certs = ca.getCertificate(subject, tidBytes);
    if (CollectionUtil.isEmpty(certs)) {
      certs = ca.getCertificate(subject, null);
    }

    if (CollectionUtil.isEmpty(certs)) {
      throw FailInfoException.BAD_CERTID;
    }

    if (certs.size() > 1) {
      LOG.warn("given certId (subject: {}) and transactionId {} match multiple certificates",
          X509Util.getRfc4519Name(subject), tid.getId());
      throw FailInfoException.BAD_CERTID;
    }

    return buildSignedData(certs.get(0));
  } // method pollCert

  private SignedData buildSignedData(X509Certificate cert) throws OperationException {
    CMSSignedDataGenerator cmsSignedDataGen = new CMSSignedDataGenerator();
    try {
      X509CertificateHolder certHolder = new X509CertificateHolder(cert.getEncoded());
      cmsSignedDataGen.addCertificate(certHolder);
      if (control.isIncludeCaCert()) {
        refreshCa();
        cmsSignedDataGen.addCertificate(caCert.getCertHolder());
      }
      CMSSignedData signedData = cmsSignedDataGen.generate(new CMSAbsentContent());
      return SignedData.getInstance(signedData.toASN1Structure().getContent());
    } catch (CMSException | IOException | CertificateEncodingException ex) {
      LogUtil.error(LOG, ex);
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }
  } // method buildSignedData

  private SignedData getCrl(X509Ca ca, BigInteger serialNumber)
      throws FailInfoException, OperationException {
    if (!control.isSupportGetCrl()) {
      throw FailInfoException.BAD_REQUEST;
    }

    CertificateList crl = ca.getBcCurrentCrl();
    if (crl == null) {
      throw FailInfoException.BAD_REQUEST;
    }
    CMSSignedDataGenerator cmsSignedDataGen = new CMSSignedDataGenerator();
    cmsSignedDataGen.addCRL(new X509CRLHolder(crl));

    CMSSignedData signedData;
    try {
      signedData = cmsSignedDataGen.generate(new CMSAbsentContent());
    } catch (CMSException ex) {
      LogUtil.error(LOG, ex, "could not generate CMSSignedData");
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }
    return SignedData.getInstance(signedData.toASN1Structure().getContent());
  } // method getCrl

  private ContentInfo encodeResponse(PkiMessage response, DecodedPkiMessage request)
      throws OperationException {
    ParamUtil.requireNonNull("response", response);
    ParamUtil.requireNonNull("request", request);

    String signatureAlgorithm = getSignatureAlgorithm(responderKey,
        request.getDigestAlgorithm());
    ContentInfo ci;
    try {
      X509Certificate[] cmsCertSet = control.isIncludeSignerCert()
          ? new X509Certificate[]{responderCert} : null;

      ci = response.encode(responderKey, signatureAlgorithm, responderCert, cmsCertSet,
          request.getSignatureCert(), request.getContentEncryptionAlgorithm());
    } catch (MessageEncodingException ex) {
      LogUtil.error(LOG, ex, "could not encode response");
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex);
    }
    return ci;
  } // method encodeResponse

  private static void checkUserPermission(ByUserRequestorInfo requestor, String certProfile)
      throws OperationException {
    int permission = PermissionConstants.ENROLL_CERT;
    if (!requestor.isPermitted(permission)) {
      throw new OperationException(ErrorCode.NOT_PERMITTED,
          PermissionConstants.getTextForCode(permission) + " is not permitted for user "
          + requestor.getCaHasUser().getUserIdent().getName());
    }

    if (!requestor.isCertProfilePermitted(certProfile)) {
      throw new OperationException(ErrorCode.NOT_PERMITTED,
          "Certificate profile " + certProfile + " is not permitted for user "
          + requestor.getCaHasUser().getUserIdent().getName());
    }
  }

  private static String getSignatureAlgorithm(PrivateKey key, ASN1ObjectIdentifier digestOid) {
    ScepHashAlgo hashAlgo = ScepHashAlgo.forNameOrOid(digestOid.getId());
    if (hashAlgo == null) {
      hashAlgo = ScepHashAlgo.SHA256;
    }
    String algorithm = key.getAlgorithm();
    if ("RSA".equalsIgnoreCase(algorithm)) {
      return hashAlgo.getName() + "withRSA";
    } else {
      throw new UnsupportedOperationException(
          "getSignatureAlgorithm() for non-RSA is not supported yet.");
    }
  } // method getSignatureAlgorithm

  private static void ensureIssuedByThisCa(X500Name thisCaX500Name, X500Name caX500Name)
      throws FailInfoException {
    if (!thisCaX500Name.equals(caX500Name)) {
      throw FailInfoException.BAD_CERTID;
    }
  }

  static CMSSignedData createDegeneratedSigendData(X509Certificate... certs)
      throws CMSException, CertificateException {
    CMSSignedDataGenerator cmsSignedDataGen = new CMSSignedDataGenerator();
    try {
      for (X509Certificate cert : certs) {
        cmsSignedDataGen.addCertificate(new X509CertificateHolder(cert.getEncoded()));
      }
      return cmsSignedDataGen.generate(new CMSAbsentContent());
    } catch (IOException ex) {
      throw new CMSException("could not build CMS SignedDta");
    }
  }

  private static byte[] getTransactionIdBytes(String tid) throws OperationException {
    byte[] bytes = null;
    final int n = tid.length();
    if (n % 2 != 0) { // neither hex nor base64 encoded
      bytes = tid.getBytes();
    } else {
      try {
        bytes = Hex.decode(tid);
      } catch (Exception ex) {
        if (n % 4 == 0) {
          try {
            bytes = Base64.decode(tid);
          } catch (Exception ex2) {
            LOG.error("could not decode (hex or base64) '{}': {}", tid, ex2.getMessage());
          }
        }
      }
    }

    if (bytes == null) {
      bytes = tid.getBytes();
    }

    if (bytes.length > 20) {
      throw new OperationException(ErrorCode.BAD_REQUEST, "transactionID too long");
    }

    return bytes;
  } // method getTransactionIdBytes

  private static void audit(AuditEvent audit, String name, String value) {
    audit.addEventData(name, (value == null) ? "null" : value);
  }

  private void refreshCa() throws OperationException {
    try {
      X509Ca ca = caManager.getX509Ca(caIdent);
      X509Cert currentCaCert = ca.getCaInfo().getCert();
      if (currentCaCert.equals(caCert)) {
        return;
      }

      caCert = currentCaCert;
      caCertRespBytes = new ScepCaCertRespBytes(currentCaCert.getCert(), responderCert);
    } catch (CaMgmtException | CertificateException | CMSException ex) {
      throw new OperationException(ErrorCode.SYSTEM_FAILURE, ex.getMessage());
    }
  }

}
