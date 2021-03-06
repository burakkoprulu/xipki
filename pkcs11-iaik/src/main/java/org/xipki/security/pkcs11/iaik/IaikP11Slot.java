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

package org.xipki.security.pkcs11.iaik;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.common.concurrent.ConcurrentBag;
import org.xipki.common.concurrent.ConcurrentBagEntry;
import org.xipki.common.util.CollectionUtil;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.security.X509Cert;
import org.xipki.security.exception.P11TokenException;
import org.xipki.security.exception.XiSecurityException;
import org.xipki.security.pkcs11.AbstractP11Slot;
import org.xipki.security.pkcs11.P11ByteArrayParams;
import org.xipki.security.pkcs11.P11EntityIdentifier;
import org.xipki.security.pkcs11.P11IVParams;
import org.xipki.security.pkcs11.P11Identity;
import org.xipki.security.pkcs11.P11MechanismFilter;
import org.xipki.security.pkcs11.P11NewKeyControl;
import org.xipki.security.pkcs11.P11ObjectIdentifier;
import org.xipki.security.pkcs11.P11Params;
import org.xipki.security.pkcs11.P11RSAPkcsPssParams;
import org.xipki.security.pkcs11.P11SlotIdentifier;
import org.xipki.security.pkcs11.P11SlotRefreshResult;
import org.xipki.security.pkcs11.Pkcs11Functions;
import org.xipki.security.util.KeyUtil;
import org.xipki.security.util.X509Util;

import iaik.pkcs.pkcs11.Mechanism;
import iaik.pkcs.pkcs11.Session;
import iaik.pkcs.pkcs11.SessionInfo;
import iaik.pkcs.pkcs11.Slot;
import iaik.pkcs.pkcs11.State;
import iaik.pkcs.pkcs11.Token;
import iaik.pkcs.pkcs11.TokenException;
import iaik.pkcs.pkcs11.constants.Functions;
import iaik.pkcs.pkcs11.constants.PKCS11Constants;
import iaik.pkcs.pkcs11.objects.Certificate.CertificateType;
import iaik.pkcs.pkcs11.objects.CharArrayAttribute;
import iaik.pkcs.pkcs11.objects.DSAPrivateKey;
import iaik.pkcs.pkcs11.objects.DSAPublicKey;
import iaik.pkcs.pkcs11.objects.ECPrivateKey;
import iaik.pkcs.pkcs11.objects.ECPublicKey;
import iaik.pkcs.pkcs11.objects.Key;
import iaik.pkcs.pkcs11.objects.KeyPair;
import iaik.pkcs.pkcs11.objects.PKCS11Object;
import iaik.pkcs.pkcs11.objects.PrivateKey;
import iaik.pkcs.pkcs11.objects.PublicKey;
import iaik.pkcs.pkcs11.objects.RSAPrivateKey;
import iaik.pkcs.pkcs11.objects.RSAPublicKey;
import iaik.pkcs.pkcs11.objects.SM2PrivateKey;
import iaik.pkcs.pkcs11.objects.SM2PublicKey;
import iaik.pkcs.pkcs11.objects.SecretKey;
import iaik.pkcs.pkcs11.objects.Storage;
import iaik.pkcs.pkcs11.objects.ValuedSecretKey;
import iaik.pkcs.pkcs11.objects.X509PublicKeyCertificate;
import iaik.pkcs.pkcs11.params.IVParams;
import iaik.pkcs.pkcs11.params.OpaqueParams;
import iaik.pkcs.pkcs11.params.Params;
import iaik.pkcs.pkcs11.params.RSAPkcsPssParams;
import iaik.pkcs.pkcs11.wrapper.PKCS11Exception;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */
class IaikP11Slot extends AbstractP11Slot {

  private static final Logger LOG = LoggerFactory.getLogger(IaikP11Slot.class);

  private static final long DEFAULT_MAX_COUNT_SESSION = 32;

  private final int maxMessageSize;

  private Slot slot;

  private final long userType;

  private List<char[]> password;

  private int maxSessionCount;

  private long timeOutWaitNewSession = 10000; // maximal wait for 10 second

  private final AtomicLong countSessions = new AtomicLong(0);

  private final ConcurrentBag<ConcurrentBagEntry<Session>> sessions = new ConcurrentBag<>();

  private boolean writableSessionInUse;

  private Session writableSession;

  IaikP11Slot(String moduleName, P11SlotIdentifier slotId, Slot slot, boolean readOnly,
      long userType, List<char[]> password, int maxMessageSize, P11MechanismFilter mechanismFilter)
      throws P11TokenException {
    super(moduleName, slotId, readOnly, mechanismFilter);
    this.slot = ParamUtil.requireNonNull("slot", slot);
    this.maxMessageSize = ParamUtil.requireMin("maxMessageSize", maxMessageSize, 1);
    this.userType = ParamUtil.requireMin("userType", userType, 0);
    this.password = password;

    Session session;
    try {
      session = openSession(false);
    } catch (P11TokenException ex) {
      LogUtil.error(LOG, ex, "openSession");
      close();
      throw ex;
    }

    try {
      firstLogin(session, password);
    } catch (P11TokenException ex) {
      LogUtil.error(LOG, ex, "firstLogin");
      close();
      throw ex;
    }

    Token token;
    try {
      token = this.slot.getToken();
    } catch (TokenException ex) {
      throw new P11TokenException("could not getToken: " + ex.getMessage(), ex);
    }

    long maxSessionCount2;
    try {
      maxSessionCount2 = token.getTokenInfo().getMaxSessionCount();
    } catch (TokenException ex) {
      throw new P11TokenException("could not get tokenInfo: " + ex.getMessage(), ex);
    }

    if (maxSessionCount2 == 0) {
      maxSessionCount2 = DEFAULT_MAX_COUNT_SESSION;
    } else {
      // 2 sessions as buffer, they may be used elsewhere.
      maxSessionCount2 = (maxSessionCount2 < 3) ? 1 : maxSessionCount2 - 2;
    }
    this.maxSessionCount = (int) maxSessionCount2;
    LOG.info("maxSessionCount: {}", this.maxSessionCount);

    sessions.add(new ConcurrentBagEntry<Session>(session));
    refresh();
  } // constructor

  Slot getSlot() {
    return slot;
  }

  @Override
  protected P11SlotRefreshResult refresh0() throws P11TokenException {
    Mechanism[] mechanisms;
    try {
      mechanisms = slot.getToken().getMechanismList();
    } catch (TokenException ex) {
      throw new P11TokenException("could not getMechanismList: " + ex.getMessage(), ex);
    }

    P11SlotRefreshResult ret = new P11SlotRefreshResult();
    if (mechanisms != null) {
      for (Mechanism mech : mechanisms) {
        ret.addMechanism(mech.getMechanismCode());
      }
    }

    ConcurrentBagEntry<Session> session = borrowSession();

    try {
      // secret keys
      List<SecretKey> secretKeys = getAllSecretKeyObjects(session.value());
      for (SecretKey secKey : secretKeys) {
        byte[] keyId = secKey.getId().getByteArrayValue();
        if (keyId == null || keyId.length == 0) {
          continue;
        }

        analyseSingleKey(secKey, ret);
      }

      // first get the list of all CA certificates
      List<X509PublicKeyCertificate> p11Certs = getAllCertificateObjects(session.value());
      for (X509PublicKeyCertificate p11Cert : p11Certs) {
        P11ObjectIdentifier objId = new P11ObjectIdentifier(
            p11Cert.getId().getByteArrayValue(), toString(p11Cert.getLabel()));
        ret.addCertificate(objId, parseCert(p11Cert));
      }

      List<PrivateKey> privKeys = getAllPrivateObjects(session.value());

      for (PrivateKey privKey : privKeys) {
        byte[] keyId = privKey.getId().getByteArrayValue();
        if (keyId == null || keyId.length == 0) {
          break;
        }

        try {
          analyseSingleKey(session.value(), privKey, ret);
        } catch (XiSecurityException ex) {
          LogUtil.error(LOG, ex, "XiSecurityException while initializing private key "
              + "with id " + hex(keyId));
          continue;
        } catch (Throwable th) {
          String label = "";
          if (privKey.getLabel() != null) {
            label = new String(privKey.getLabel().getCharArrayValue());
          }
          LOG.error("unexpected exception while initializing private key with id "
              + hex(keyId) + " and label " + label, th);
          continue;
        }
      }

      return ret;
    } finally {
      sessions.requite(session);
    }
  } // method refresh

  @Override
  public void close() {
    if (slot != null) {
      try {
        LOG.info("close all sessions on token: {}", slot.getSlotID());

        if (writableSession != null) {
          writableSession.closeSession();
        }

        for (ConcurrentBagEntry<Session> session : sessions.values()) {
          session.value().closeSession();
        }
      } catch (Throwable th) {
        LogUtil.error(LOG, th, "could not slot.getToken().closeAllSessions()");
      }

      slot = null;
    }

    // clear the session pool
    sessions.close();
    countSessions.lazySet(0);
  }

  private void analyseSingleKey(SecretKey secretKey, P11SlotRefreshResult refreshResult) {
    byte[] id = secretKey.getId().getByteArrayValue();
    P11ObjectIdentifier objectId = new P11ObjectIdentifier(id, toString(secretKey.getLabel()));

    IaikP11Identity identity = new IaikP11Identity(this,
        new P11EntityIdentifier(slotId, objectId), secretKey);
    refreshResult.addIdentity(identity);
  }

  private void analyseSingleKey(Session session, PrivateKey privKey,
      P11SlotRefreshResult refreshResult) throws P11TokenException, XiSecurityException {
    byte[] id = privKey.getId().getByteArrayValue();
    java.security.PublicKey pubKey = null;
    X509Cert cert = refreshResult.getCertForId(id);
    if (cert != null) {
      pubKey = cert.getCert().getPublicKey();
    } else {
      PublicKey p11PublicKey = getPublicKeyObject(session, id, null);
      if (p11PublicKey == null) {
        LOG.info("neither certificate nor public key for the key (" + hex(id)
            + " is available");
        return;
      }

      pubKey = generatePublicKey(p11PublicKey);
    }

    P11ObjectIdentifier objectId = new P11ObjectIdentifier(id, toString(privKey.getLabel()));

    X509Certificate[] certs = (cert == null) ? null : new X509Certificate[]{cert.getCert()};
    IaikP11Identity identity = new IaikP11Identity(this,
        new P11EntityIdentifier(slotId, objectId), privKey, pubKey, certs);
    refreshResult.addIdentity(identity);
  }

  byte[] digestKey(long mechanism, IaikP11Identity identity) throws P11TokenException {
    ParamUtil.requireNonNull("identity", identity);
    assertMechanismSupported(mechanism);
    Key signingKey = identity.getSigningKey();
    if (!(signingKey instanceof SecretKey)) {
      throw new P11TokenException("digestSecretKey could not be applied to non-SecretKey");
    }

    if (LOG.isTraceEnabled()) {
      LOG.debug("digest (init, digestKey, then finish)\n{}", signingKey);
    }

    int digestLen;
    if (PKCS11Constants.CKM_SHA_1 == mechanism) {
      digestLen = 20;
    } else if (PKCS11Constants.CKM_SHA224 == mechanism
        || PKCS11Constants.CKM_SHA3_224 == mechanism) {
      digestLen = 28;
    } else if (PKCS11Constants.CKM_SHA256 == mechanism
        || PKCS11Constants.CKM_SHA3_256 == mechanism) {
      digestLen = 32;
    } else if (PKCS11Constants.CKM_SHA384 == mechanism
        || PKCS11Constants.CKM_SHA3_384 == mechanism) {
      digestLen = 48;
    } else if (PKCS11Constants.CKM_SHA512 == mechanism
        || PKCS11Constants.CKM_SHA3_512 == mechanism) {
      digestLen = 64;
    } else {
      throw new P11TokenException("unsupported mechnism " + mechanism);
    }

    ConcurrentBagEntry<Session> session0 = borrowSession();

    try {
      Session session = session0.value();
      session.digestInit(Mechanism.get(mechanism));
      session.digestKey((SecretKey) signingKey);
      byte[] digest = new byte[digestLen];
      session.digestFinal(digest, 0, digestLen);
      return digest;
    } catch (TokenException ex) {
      throw new P11TokenException(ex);
    } finally {
      sessions.requite(session0);
    }
  }

  byte[] sign(long mechanism, P11Params parameters, byte[] content, IaikP11Identity identity)
      throws P11TokenException {
    ParamUtil.requireNonNull("content", content);
    assertMechanismSupported(mechanism);

    int len = content.length;
    int expectedSignatureLen;
    if (mechanism == PKCS11Constants.CKM_SHA_1_HMAC) {
      expectedSignatureLen = 20;
    } else if (mechanism == PKCS11Constants.CKM_SHA224_HMAC
        || mechanism == PKCS11Constants.CKM_SHA3_224) {
      expectedSignatureLen = 28;
    } else if (mechanism == PKCS11Constants.CKM_SHA256_HMAC
        || mechanism == PKCS11Constants.CKM_SHA3_256) {
      expectedSignatureLen = 32;
    } else if (mechanism == PKCS11Constants.CKM_SHA384_HMAC
        || mechanism == PKCS11Constants.CKM_SHA3_384) {
      expectedSignatureLen = 48;
    } else if (mechanism == PKCS11Constants.CKM_SHA512_HMAC
        || mechanism == PKCS11Constants.CKM_SHA3_512) {
      expectedSignatureLen = 64;
    } else if (mechanism == PKCS11Constants.CKM_VENDOR_SM2
        || mechanism == PKCS11Constants.CKM_VENDOR_SM2_SM3) {
      expectedSignatureLen = 32;
    } else {
      expectedSignatureLen = identity.getExpectedSignatureLen();
    }

    ConcurrentBagEntry<Session> session0 = borrowSession();

    try {
      Session session = session0.value();
      if (len <= maxMessageSize) {
        return singleSign(session, mechanism, parameters, content, identity);
      }

      Key signingKey = identity.getSigningKey();
      Mechanism mechanismObj = getMechanism(mechanism, parameters);
      if (LOG.isTraceEnabled()) {
        LOG.debug("sign (init, update, then finish) with private key:\n{}", signingKey);
      }

      session.signInit(mechanismObj, signingKey);
      for (int i = 0; i < len; i += maxMessageSize) {
        int blockLen = Math.min(maxMessageSize, len - i);
        //byte[] block = new byte[blockLen];
        //System.arraycopy(content, i, block, 0, blockLen);
        session.signUpdate(content, i, blockLen);
      }

      return session.signFinal(expectedSignatureLen);
    } catch (TokenException ex) {
      throw new P11TokenException(ex);
    } finally {
      sessions.requite(session0);
    }
  }

  private byte[] singleSign(Session session, long mechanism, P11Params parameters, byte[] content,
      IaikP11Identity identity) throws P11TokenException {
    Key signingKey = identity.getSigningKey();
    Mechanism mechanismObj = getMechanism(mechanism, parameters);
    if (LOG.isTraceEnabled()) {
      LOG.debug("sign with signing key:\n{}", signingKey);
    }

    byte[] signature;
    try {
      session.signInit(mechanismObj, signingKey);
      signature = session.sign(content);
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("signature:\n{}", hex(signature));
    }
    return signature;
  }

  private static Mechanism getMechanism(long mechanism, P11Params parameters)
      throws P11TokenException {
    Mechanism ret = Mechanism.get(mechanism);
    if (parameters == null) {
      return ret;
    }

    Params paramObj;
    if (parameters instanceof P11RSAPkcsPssParams) {
      P11RSAPkcsPssParams param = (P11RSAPkcsPssParams) parameters;
      paramObj = new RSAPkcsPssParams(Mechanism.get(param.getHashAlgorithm()),
          param.getMaskGenerationFunction(), param.getSaltLength());
    } else if (parameters instanceof P11ByteArrayParams) {
      paramObj = new OpaqueParams(((P11ByteArrayParams) parameters).getBytes());
    } else if (parameters instanceof P11IVParams) {
      paramObj = new IVParams(((P11IVParams) parameters).getIV());
    } else {
      throw new P11TokenException("unknown P11Parameters " + parameters.getClass().getName());
    }

    if (paramObj != null) {
      ret.setParams(paramObj);
    }

    return ret;
  }

  private Session openSession(boolean rwSession) throws P11TokenException {
    Session session;
    try {
      session = slot.getToken().openSession(Token.SessionType.SERIAL_SESSION, rwSession, null,
          null);
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
    countSessions.incrementAndGet();
    return session;
  }

  private ConcurrentBagEntry<Session> borrowSession() throws P11TokenException {
    ConcurrentBagEntry<Session> session = null;
    if (countSessions.get() < maxSessionCount) {
      try {
        session = sessions.borrow(1, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ex) { // CHECKSTYLE:SKIP
      }

      if (session == null) {
        // create new session
        session = new ConcurrentBagEntry<>(openSession(false));
        sessions.add(session);
      }
    }

    if (session == null) {
      try {
        session = sessions.borrow(timeOutWaitNewSession, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ex) { // CHECKSTYLE:SKIP
      }
    }

    if (session == null) {
      throw new P11TokenException("no idle session");
    }
    login(session.value());
    return session;
  }

  private void firstLogin(Session session, List<char[]> password) throws P11TokenException {
    try {
      boolean isProtectedAuthenticationPath =
          session.getToken().getTokenInfo().isProtectedAuthenticationPath();

      if (isProtectedAuthenticationPath || CollectionUtil.isEmpty(password)) {
        LOG.info("verify on PKCS11Module with PROTECTED_AUTHENTICATION_PATH");
        singleLogin(session, null);
      } else {
        LOG.info("verify on PKCS11Module with PIN");
        for (char[] singlePwd : password) {
          singleLogin(session, singlePwd);
        }
        this.password = password;
      }
    } catch (PKCS11Exception ex) {
      // 0x100: user already logged in
      if (ex.getErrorCode() != 0x100) {
        throw new P11TokenException(ex.getMessage(), ex);
      }
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
  }

  private void login(Session session) throws P11TokenException {
    boolean isSessionLoggedIn = checkSessionLoggedIn(session);
    if (isSessionLoggedIn) {
      return;
    }

    boolean loginRequired;
    try {
      loginRequired = session.getToken().getTokenInfo().isLoginRequired();
    } catch (TokenException ex) {
      LogUtil.error(LOG, ex, "could not check whether LoginRequired of token");
      loginRequired = true;
    }

    LOG.debug("loginRequired: {}", loginRequired);
    if (!loginRequired) {
      return;
    }

    if (CollectionUtil.isEmpty(password)) {
      singleLogin(session, null);
    } else {
      for (char[] singlePwd : password) {
        singleLogin(session, singlePwd);
      }
    }
  }

  private void singleLogin(Session session, char[] pin) throws P11TokenException {
    char[] tmpPin = pin;
    // some driver does not accept null PIN
    if (pin == null) {
      tmpPin = new char[]{};
    }

    try {
      session.login(userType, tmpPin);
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
  }

  private List<PrivateKey> getAllPrivateObjects(Session session) throws P11TokenException {
    PrivateKey template = new PrivateKey();
    List<Storage> tmpObjects = getObjects(session, template);
    if (CollectionUtil.isEmpty(tmpObjects)) {
      return Collections.emptyList();
    }

    final int n = tmpObjects.size();
    LOG.info("found {} private keys", n);

    List<PrivateKey> privateKeys = new ArrayList<>(n);
    for (Storage tmpObject : tmpObjects) {
      PrivateKey privateKey = (PrivateKey) tmpObject;
      privateKeys.add(privateKey);
    }

    return privateKeys;
  }

  private List<SecretKey> getAllSecretKeyObjects(Session session) throws P11TokenException {
    SecretKey template = new SecretKey();
    List<Storage> tmpObjects = getObjects(session, template);
    if (CollectionUtil.isEmpty(tmpObjects)) {
      return Collections.emptyList();
    }

    final int n = tmpObjects.size();
    LOG.info("found {} private keys", n);

    List<SecretKey> keys = new ArrayList<>(n);
    for (Storage tmpObject : tmpObjects) {
      SecretKey key = (SecretKey) tmpObject;
      keys.add(key);
    }

    return keys;
  }

  private SecretKey getSecretKeyObject(Session session, byte[] keyId, char[] keyLabel)
      throws P11TokenException {
    return (SecretKey) getKeyObject(session, new SecretKey(), keyId, keyLabel);
  }

  private PrivateKey getPrivateKeyObject(Session session, byte[] keyId, char[] keyLabel)
      throws P11TokenException {
    return (PrivateKey) getKeyObject(session, new PrivateKey(), keyId, keyLabel);
  }

  private PublicKey getPublicKeyObject(Session session, byte[] keyId, char[] keyLabel)
      throws P11TokenException {
    return (PublicKey) getKeyObject(session, new PublicKey(), keyId, keyLabel);
  }

  private Key getKeyObject(Session session, Key template, byte[] keyId, char[] keyLabel)
      throws P11TokenException {
    if (keyId != null) {
      template.getId().setByteArrayValue(keyId);
    }
    if (keyLabel != null) {
      template.getLabel().setCharArrayValue(keyLabel);
    }

    List<Storage> tmpObjects = getObjects(session, template, 2);
    if (CollectionUtil.isEmpty(tmpObjects)) {
      return null;
    }
    int size = tmpObjects.size();
    if (size > 1) {
      LOG.warn("found {} public key identified by {}, use the first one", size,
          getDescription(keyId, keyLabel));
    }

    return (Key) tmpObjects.get(0);
  }

  private static boolean checkSessionLoggedIn(Session session) throws P11TokenException {
    SessionInfo info;
    try {
      info = session.getSessionInfo();
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
    if (LOG.isTraceEnabled()) {
      LOG.debug("SessionInfo: {}", info);
    }

    State state = info.getState();
    long deviceError = info.getDeviceError();

    LOG.debug("to be verified PKCS11Module: state = {}, deviceError: {}", state, deviceError);

    boolean isRwSessionLoggedIn = state.equals(State.RW_USER_FUNCTIONS);
    boolean isRoSessionLoggedIn = state.equals(State.RO_USER_FUNCTIONS);

    boolean sessionLoggedIn = ((isRoSessionLoggedIn || isRwSessionLoggedIn) && deviceError == 0);
    LOG.debug("sessionLoggedIn: {}", sessionLoggedIn);
    return sessionLoggedIn;
  }

  private static List<Storage> getObjects(Session session, Storage template)
      throws P11TokenException {
    return getObjects(session, template, 9999);
  }

  private static List<Storage> getObjects(Session session, Storage template, int maxNo)
      throws P11TokenException {
    List<Storage> objList = new LinkedList<>();

    try {
      session.findObjectsInit(template);

      while (objList.size() < maxNo) {
        PKCS11Object[] foundObjects = session.findObjects(1);
        if (foundObjects == null || foundObjects.length == 0) {
          break;
        }

        for (PKCS11Object object : foundObjects) {
          if (LOG.isTraceEnabled()) {
            LOG.debug("found object: {}", object);
          }
          objList.add((Storage) object);
        }
      }
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      try {
        session.findObjectsFinal();
      } catch (Exception ex) {
        LogUtil.error(LOG, ex, "session.findObjectsFinal() failed");
      }
    }

    return objList;
  } // method getObjects

  private static java.security.PublicKey generatePublicKey(PublicKey p11Key)
      throws XiSecurityException {
    if (p11Key instanceof RSAPublicKey) {
      RSAPublicKey rsaP11Key = (RSAPublicKey) p11Key;
      byte[] expBytes = rsaP11Key.getPublicExponent().getByteArrayValue();
      BigInteger exp = new BigInteger(1, expBytes);

      byte[] modBytes = rsaP11Key.getModulus().getByteArrayValue();
      BigInteger mod = new BigInteger(1, modBytes);
      RSAPublicKeySpec keySpec = new RSAPublicKeySpec(mod, exp);
      try {
        return KeyUtil.generateRSAPublicKey(keySpec);
      } catch (InvalidKeySpecException ex) {
        throw new XiSecurityException(ex.getMessage(), ex);
      }
    } else if (p11Key instanceof DSAPublicKey) {
      DSAPublicKey dsaP11Key = (DSAPublicKey) p11Key;

      BigInteger prime = new BigInteger(1, dsaP11Key.getPrime().getByteArrayValue()); // p
      BigInteger subPrime = new BigInteger(1,
          dsaP11Key.getSubprime().getByteArrayValue()); // q
      BigInteger base = new BigInteger(1, dsaP11Key.getBase().getByteArrayValue()); // g
      BigInteger value = new BigInteger(1, dsaP11Key.getValue().getByteArrayValue()); // y
      DSAPublicKeySpec keySpec = new DSAPublicKeySpec(value, prime, subPrime, base);
      try {
        return KeyUtil.generateDSAPublicKey(keySpec);
      } catch (InvalidKeySpecException ex) {
        throw new XiSecurityException(ex.getMessage(), ex);
      }
    } else if (p11Key instanceof ECPublicKey) {
      ECPublicKey ecP11Key = (ECPublicKey) p11Key;
      byte[] encodedAlgorithmIdParameters = ecP11Key.getEcdsaParams().getByteArrayValue();
      byte[] encodedPoint = DEROctetString.getInstance(
          ecP11Key.getEcPoint().getByteArrayValue()).getOctets();
      try {
        return KeyUtil.createECPublicKey(encodedAlgorithmIdParameters, encodedPoint);
      } catch (InvalidKeySpecException ex) {
        throw new XiSecurityException(ex.getMessage(), ex);
      }
    } else {
      throw new XiSecurityException("unknown publicKey class " + p11Key.getClass().getName());
    }
  } // method generatePublicKey

  private static String toString(CharArrayAttribute charArrayAttr) {
    String labelStr = "";
    if (charArrayAttr != null) {
      char[] chars = charArrayAttr.getCharArrayValue();
      if (chars != null) {
        labelStr = new String(chars);
      }
    }
    return labelStr;
  }

  private static X509Cert parseCert(X509PublicKeyCertificate p11Cert) throws P11TokenException {
    try {
      byte[] encoded = p11Cert.getValue().getByteArrayValue();
      return new X509Cert(X509Util.parseCert(encoded), encoded);
    } catch (CertificateException ex) {
      throw new P11TokenException("could not parse certificate: " + ex.getMessage(), ex);
    }
  }

  private synchronized Session borrowWritableSession() throws P11TokenException {
    if (writableSession == null) {
      writableSession = openSession(true);
    }

    if (writableSessionInUse) {
      throw new P11TokenException("no idle writable session available");
    }

    writableSessionInUse = true;
    login(writableSession);
    return writableSession;
  }

  private synchronized void returnWritableSession(Session session) throws P11TokenException {
    if (session != writableSession) {
      throw new P11TokenException("the returned session does not belong to me");
    }
    this.writableSessionInUse = false;
  }

  private List<X509PublicKeyCertificate> getAllCertificateObjects(Session session)
      throws P11TokenException {
    X509PublicKeyCertificate template = new X509PublicKeyCertificate();
    List<Storage> tmpObjects = getObjects(session, template);

    List<X509PublicKeyCertificate> certs = new ArrayList<>(tmpObjects.size());
    for (PKCS11Object tmpObject : tmpObjects) {
      X509PublicKeyCertificate cert = (X509PublicKeyCertificate) tmpObject;
      certs.add(cert);
    }
    return certs;
  }

  @Override
  public int removeObjects(byte[] id, String label) throws P11TokenException {
    if ((id == null || id.length == 0) && StringUtil.isBlank(label)) {
      throw new IllegalArgumentException("at least one of id and label must not be null");
    }

    Key keyTemplate = new Key();
    if (id != null && id.length > 0) {
      keyTemplate.getId().setByteArrayValue(id);
    }
    if (StringUtil.isNotBlank(label)) {
      keyTemplate.getLabel().setCharArrayValue(label.toCharArray());
    }

    String objIdDesc = getDescription(id, label);
    int num = removeObjects(keyTemplate, "keys " + objIdDesc);

    X509PublicKeyCertificate certTemplate = new X509PublicKeyCertificate();
    if (id != null && id.length > 0) {
      certTemplate.getId().setByteArrayValue(id);
    }
    if (StringUtil.isNotBlank(label)) {
      certTemplate.getLabel().setCharArrayValue(label.toCharArray());
    }

    num += removeObjects(certTemplate, "certificates" + objIdDesc);
    return num;
  }

  private int removeObjects(Storage template, String desc) throws P11TokenException {
    Session session = borrowWritableSession();
    try {
      List<Storage> objects = getObjects(session, template);
      for (Storage obj : objects) {
        session.destroyObject(obj);
      }
      return objects.size();
    } catch (TokenException ex) {
      LogUtil.error(LOG, ex, "could not remove " + desc);
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      returnWritableSession(session);
    }
  }

  @Override
  protected void removeCerts0(P11ObjectIdentifier objectId) throws P11TokenException {
    Session session = borrowWritableSession();

    try {
      X509PublicKeyCertificate[] existingCerts = getCertificateObjects(session, objectId.getId(),
          objectId.getLabelChars());
      if (existingCerts == null || existingCerts.length == 0) {
        LOG.warn("could not find certificates " + objectId);
        return;
      }

      for (X509PublicKeyCertificate cert : existingCerts) {
        session.destroyObject(cert);
      }
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      returnWritableSession(session);
    }
  }

  @Override
  protected void addCert0(P11ObjectIdentifier objectId, X509Certificate cert)
      throws P11TokenException {
    X509PublicKeyCertificate newCaCertTemp = createPkcs11Template(
        new X509Cert(cert), objectId.getId(), objectId.getLabelChars());
    Session session = borrowWritableSession();
    try {
      session.createObject(newCaCertTemp);
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      returnWritableSession(session);
    }
  }

  @Override
  protected P11Identity generateSecretKey0(long keyType, int keysize, String label,
      P11NewKeyControl control) throws P11TokenException {
    if (keysize % 8 != 0) {
      throw new IllegalArgumentException("keysize is not multiple of 8: " + keysize);
    }

    long mech;
    if (PKCS11Constants.CKK_AES == keyType) {
      mech = PKCS11Constants.CKM_AES_KEY_GEN;
    } else if (PKCS11Constants.CKK_DES3 == keyType) {
      mech = PKCS11Constants.CKM_DES3_KEY_GEN;
    } else if (PKCS11Constants.CKK_GENERIC_SECRET == keyType) {
      mech = PKCS11Constants.CKM_GENERIC_SECRET_KEY_GEN;
    } else if (PKCS11Constants.CKK_SHA_1_HMAC == keyType
        || PKCS11Constants.CKK_SHA224_HMAC == keyType
        || PKCS11Constants.CKK_SHA256_HMAC == keyType
        || PKCS11Constants.CKK_SHA384_HMAC == keyType
        || PKCS11Constants.CKK_SHA512_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_224_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_256_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_384_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_512_HMAC == keyType) {
      mech = PKCS11Constants.CKM_GENERIC_SECRET_KEY_GEN;
    } else {
      throw new IllegalArgumentException(
          "unsupported key type 0x" + Functions.toFullHex((int)keyType));
    }

    assertMechanismSupported(mech);

    ValuedSecretKey template = new ValuedSecretKey(keyType);

    template.getToken().setBooleanValue(true);
    template.getLabel().setCharArrayValue(label.toCharArray());
    template.getSign().setBooleanValue(true);
    template.getSensitive().setBooleanValue(true);
    template.getExtractable().setBooleanValue(control.isExtractable());
    template.getValueLen().setLongValue((long) (keysize / 8));

    Mechanism mechanism = Mechanism.get(mech);
    SecretKey key;
    Session session = borrowWritableSession();
    try {
      if (labelExists(session, label)) {
        throw new IllegalArgumentException(
            "label " + label + " exists, please specify another one");
      }

      byte[] id = generateKeyId(session);
      template.getId().setByteArrayValue(id);
      try {
        key = (SecretKey) session.generateKey(mechanism, template);
      } catch (TokenException ex) {
        throw new P11TokenException("could not generate generic secret key using "
            + mechanism.getName(), ex);
      }

      P11ObjectIdentifier objId = new P11ObjectIdentifier(id, label);
      P11EntityIdentifier entityId = new P11EntityIdentifier(slotId, objId);

      return new IaikP11Identity(this, entityId, key);
    } finally {
      returnWritableSession(session);
    }
  }

  @Override
  protected P11Identity importSecretKey0(long keyType, byte[] keyValue, String label,
      P11NewKeyControl control) throws P11TokenException {
    ValuedSecretKey template = new ValuedSecretKey(keyType);
    template.getToken().setBooleanValue(true);
    template.getLabel().setCharArrayValue(label.toCharArray());
    template.getSign().setBooleanValue(true);
    template.getSensitive().setBooleanValue(true);
    template.getExtractable().setBooleanValue(control.isExtractable());
    template.getValue().setByteArrayValue(keyValue);

    SecretKey key;
    Session session = borrowWritableSession();
    try {
      if (labelExists(session, label)) {
        throw new IllegalArgumentException(
            "label " + label + " exists, please specify another one");
      }

      byte[] id = generateKeyId(session);
      template.getId().setByteArrayValue(id);
      try {
        key = (SecretKey) session.createObject(template);
      } catch (TokenException ex) {
        throw new P11TokenException("could not create secret key", ex);
      }

      P11ObjectIdentifier objId = new P11ObjectIdentifier(id, label);
      P11EntityIdentifier entityId = new P11EntityIdentifier(slotId, objId);

      return new IaikP11Identity(this, entityId, key);
    } finally {
      returnWritableSession(session);
    }
  }

  @Override
  protected P11Identity generateRSAKeypair0(int keysize, BigInteger publicExponent,
      String label, P11NewKeyControl control) throws P11TokenException {
    long mech = PKCS11Constants.CKM_RSA_PKCS_KEY_PAIR_GEN;
    assertMechanismSupported(mech);

    RSAPrivateKey privateKey = new RSAPrivateKey();
    RSAPublicKey publicKey = new RSAPublicKey();
    setKeyAttributes(label, PKCS11Constants.CKK_RSA, control, publicKey, privateKey);

    publicKey.getModulusBits().setLongValue((long) keysize);
    if (publicExponent != null) {
      publicKey.getPublicExponent().setByteArrayValue(publicExponent.toByteArray());
    }

    return generateKeyPair(mech, privateKey, publicKey);
  }

  @Override
  // CHECKSTYLE:SKIP
  protected P11Identity generateDSAKeypair0(BigInteger p, BigInteger q, BigInteger g,
      String label, P11NewKeyControl control) throws P11TokenException {
    long mech = PKCS11Constants.CKM_DSA_KEY_PAIR_GEN;
    assertMechanismSupported(mech);

    DSAPrivateKey privateKey = new DSAPrivateKey();
    DSAPublicKey publicKey = new DSAPublicKey();
    setKeyAttributes(label, PKCS11Constants.CKK_DSA, control, publicKey, privateKey);

    publicKey.getPrime().setByteArrayValue(p.toByteArray());
    publicKey.getSubprime().setByteArrayValue(q.toByteArray());
    publicKey.getBase().setByteArrayValue(g.toByteArray());
    return generateKeyPair(mech, privateKey, publicKey);
  }

  @Override
  protected P11Identity generateECKeypair0(ASN1ObjectIdentifier curveId, String label,
      P11NewKeyControl control) throws P11TokenException {
    long mech = PKCS11Constants.CKM_EC_KEY_PAIR_GEN;
    assertMechanismSupported(mech);

    ECPrivateKey privateKey = new ECPrivateKey();
    ECPublicKey publicKey = new ECPublicKey();
    setKeyAttributes(label, PKCS11Constants.CKK_EC, control, publicKey, privateKey);
    byte[] encodedCurveId;
    try {
      encodedCurveId = curveId.getEncoded();
    } catch (IOException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
    try {
      publicKey.getEcdsaParams().setByteArrayValue(encodedCurveId);
      return generateKeyPair(mech, privateKey, publicKey);
    } catch (P11TokenException ex) {
      X9ECParameters ecParams = ECNamedCurveTable.getByOID(curveId);
      if (ecParams == null) {
        throw new IllegalArgumentException("could not get X9ECParameters for curve "
            + curveId.getId());
      }

      try {
        publicKey.getEcdsaParams().setByteArrayValue(ecParams.getEncoded());
      } catch (IOException ex2) {
        throw new P11TokenException(ex.getMessage(), ex);
      }
      return generateKeyPair(mech, privateKey, publicKey);
    }
  }

  @Override
  protected P11Identity generateSM2Keypair0(String label, P11NewKeyControl control)
      throws P11TokenException {
    long mech = PKCS11Constants.CKM_VENDOR_SM2_KEY_PAIR_GEN;
    assertMechanismSupported(mech);

    SM2PrivateKey privateKey = new SM2PrivateKey();
    SM2PublicKey publicKey = new SM2PublicKey();
    setKeyAttributes(label, PKCS11Constants.CKK_VENDOR_SM2, control, publicKey, privateKey);
    return generateKeyPair(mech, privateKey, publicKey);
  }

  private P11Identity generateKeyPair(long mech, PrivateKey privateKey, PublicKey publicKey)
      throws P11TokenException {
    final String label = toString(privateKey.getLabel());
    byte[] id = null;

    try {
      KeyPair keypair;
      Session session = borrowWritableSession();
      try {
        if (labelExists(session, label)) {
          throw new IllegalArgumentException(
              "label " + label + " exists, please specify another one");
        }

        id = generateKeyId(session);
        privateKey.getId().setByteArrayValue(id);
        publicKey.getId().setByteArrayValue(id);
        try {
          keypair = session.generateKeyPair(Mechanism.get(mech), publicKey, privateKey);
        } catch (TokenException ex) {
          throw new P11TokenException("could not generate keypair "
              + Pkcs11Functions.mechanismCodeToString(mech), ex);
        }

        P11ObjectIdentifier objId = new P11ObjectIdentifier(id, label);
        P11EntityIdentifier entityId = new P11EntityIdentifier(slotId, objId);
        java.security.PublicKey jcePublicKey;
        try {
          jcePublicKey = generatePublicKey(keypair.getPublicKey());
        } catch (XiSecurityException ex) {
          throw new P11TokenException("could not generate public key " + objId, ex);
        }

        PrivateKey privateKey2 = getPrivateKeyObject(session, id, label.toCharArray());
        if (privateKey2 == null) {
          throw new P11TokenException("could not read the generated private key");
        }
        return new IaikP11Identity(this, entityId, privateKey2, jcePublicKey, null);
      } finally {
        returnWritableSession(session);
      }
    } catch (P11TokenException | RuntimeException ex) {
      try {
        removeObjects(id, label);
      } catch (Throwable th) {
        LogUtil.error(LOG, th, "could not remove objects");
      }
      throw ex;
    }
  }

  private static X509PublicKeyCertificate createPkcs11Template(X509Cert cert,
      byte[] keyId, char[] label) {
    if (label == null || label.length == 0) {
      throw new IllegalArgumentException("label must not be null or empty");
    }

    X509PublicKeyCertificate newCertTemp = new X509PublicKeyCertificate();
    newCertTemp.getId().setByteArrayValue(keyId);
    newCertTemp.getLabel().setCharArrayValue(label);
    newCertTemp.getToken().setBooleanValue(true);
    newCertTemp.getCertificateType().setLongValue(CertificateType.X_509_PUBLIC_KEY);
    newCertTemp.getSubject().setByteArrayValue(
        cert.getCert().getSubjectX500Principal().getEncoded());
    newCertTemp.getIssuer().setByteArrayValue(cert.getCert().getIssuerX500Principal().getEncoded());
    newCertTemp.getSerialNumber().setByteArrayValue(cert.getCert().getSerialNumber().toByteArray());
    newCertTemp.getValue().setByteArrayValue(cert.getEncodedCert());
    return newCertTemp;
  }

  private static void setKeyAttributes(String label, long keyType, P11NewKeyControl control,
      PublicKey publicKey, PrivateKey privateKey) {
    if (privateKey != null) {
      privateKey.getToken().setBooleanValue(true);
      privateKey.getLabel().setCharArrayValue(label.toCharArray());
      privateKey.getKeyType().setLongValue(keyType);
      privateKey.getSign().setBooleanValue(true);
      privateKey.getPrivate().setBooleanValue(true);
      privateKey.getSensitive().setBooleanValue(true);
      privateKey.getExtractable().setBooleanValue(control.isExtractable());
    }

    if (publicKey != null) {
      publicKey.getToken().setBooleanValue(true);
      publicKey.getLabel().setCharArrayValue(label.toCharArray());
      publicKey.getKeyType().setLongValue(keyType);
      publicKey.getVerify().setBooleanValue(true);
      publicKey.getModifiable().setBooleanValue(Boolean.TRUE);
    }
  }

  @Override
  protected void updateCertificate0(P11ObjectIdentifier objectId, X509Certificate newCert)
      throws P11TokenException {
    removeCerts(objectId);
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      // CHECKSTYLE:SKIP
    }

    X509PublicKeyCertificate newCertTemp = createPkcs11Template(new X509Cert(newCert),
        objectId.getId(), objectId.getLabelChars());

    Session session = borrowWritableSession();
    try {
      session.createObject(newCertTemp);
    } catch (TokenException ex) {
      throw new P11TokenException("could not createObject: " + ex.getMessage(), ex);
    } finally {
      returnWritableSession(session);
    }
  }

  private X509PublicKeyCertificate[] getCertificateObjects(Session session, byte[] keyId,
      char[] keyLabel) throws P11TokenException {
    X509PublicKeyCertificate template = new X509PublicKeyCertificate();
    if (keyId != null) {
      template.getId().setByteArrayValue(keyId);
    }
    if (keyLabel != null) {
      template.getLabel().setCharArrayValue(keyLabel);
    }

    List<Storage> tmpObjects = getObjects(session, template);

    if (CollectionUtil.isEmpty(tmpObjects)) {
      LOG.info("found no certificate identified by {}", getDescription(keyId, keyLabel));
      return null;
    }

    int size = tmpObjects.size();
    X509PublicKeyCertificate[] certs = new X509PublicKeyCertificate[size];
    for (int i = 0; i < size; i++) {
      certs[i] = (X509PublicKeyCertificate) tmpObjects.get(i);
    }
    return certs;
  }

  @Override
  protected void removeIdentity0(P11ObjectIdentifier objectId) throws P11TokenException {
    Session session = borrowWritableSession();
    try {
      byte[] id = objectId.getId();
      char[] label = objectId.getLabelChars();
      SecretKey secretKey = getSecretKeyObject(session, id, label);
      if (secretKey != null) {
        try {
          session.destroyObject(secretKey);
        } catch (TokenException ex) {
          String msg = "could not delete secret key " + objectId;
          LogUtil.error(LOG, ex, msg);
          throw new P11TokenException(msg);
        }
      }

      PrivateKey privKey = getPrivateKeyObject(session, id, label);
      if (privKey != null) {
        try {
          session.destroyObject(privKey);
        } catch (TokenException ex) {
          String msg = "could not delete private key " + objectId;
          LogUtil.error(LOG, ex, msg);
          throw new P11TokenException(msg);
        }
      }

      PublicKey pubKey = getPublicKeyObject(session, id, label);
      if (pubKey != null) {
        try {
          session.destroyObject(pubKey);
        } catch (TokenException ex) {
          String msg = "could not delete public key " + objectId;
          LogUtil.error(LOG, ex, msg);
          throw new P11TokenException(msg);
        }
      }

      X509PublicKeyCertificate[] certs = getCertificateObjects(session, id, label);
      if (certs != null && certs.length > 0) {
        for (int i = 0; i < certs.length; i++) {
          try {
            session.destroyObject(certs[i]);
          } catch (TokenException ex) {
            String msg = "could not delete certificate " + objectId;
            LogUtil.error(LOG, ex, msg);
            throw new P11TokenException(msg);
          }
        }
      }
    } finally {
      returnWritableSession(session);
    }
  }

  private static byte[] generateKeyId(Session session) throws P11TokenException {
    SecureRandom random = new SecureRandom();
    byte[] keyId = null;
    do {
      keyId = new byte[8];
      random.nextBytes(keyId);
    } while (idExists(session, keyId));

    return keyId;
  }

  private static boolean idExists(Session session, byte[] keyId) throws P11TokenException {
    Key key = new Key();
    key.getId().setByteArrayValue(keyId);

    Object[] objects;
    try {
      session.findObjectsInit(key);
      objects = session.findObjects(1);
      if (objects.length > 0) {
        return true;
      }
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      try {
        session.findObjectsFinal();
      } catch (TokenException ex) {
        LogUtil.error(LOG, ex, "session.findObjectsFinal() failed");
      }
    }

    X509PublicKeyCertificate cert = new X509PublicKeyCertificate();
    cert.getId().setByteArrayValue(keyId);

    try {
      session.findObjectsInit(cert);
      objects = session.findObjects(1);
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      try {
        session.findObjectsFinal();
      } catch (TokenException ex) {
        LogUtil.error(LOG, ex, "session.findObjectsFinal() failed");
      }
    }

    return objects.length > 0;
  }

  private static boolean labelExists(Session session, String keyLabel) throws P11TokenException {
    ParamUtil.requireNonBlank("keyLabel", keyLabel);
    Key key = new Key();
    key.getLabel().setCharArrayValue(keyLabel.toCharArray());

    Object[] objects;
    try {
      session.findObjectsInit(key);
      objects = session.findObjects(1);
      if (objects.length > 0) {
        return true;
      }
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      try {
        session.findObjectsFinal();
      } catch (TokenException ex) {
        LogUtil.error(LOG, ex, "session.findObjectsFinal() failed");
      }
    }

    X509PublicKeyCertificate cert = new X509PublicKeyCertificate();
    cert.getLabel().setCharArrayValue(keyLabel.toCharArray());

    try {
      session.findObjectsInit(cert);
      objects = session.findObjects(1);
    } catch (TokenException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    } finally {
      try {
        session.findObjectsFinal();
      } catch (TokenException ex) {
        LogUtil.error(LOG, ex, "session.findObjectsFinal() failed");
      }
    }

    return objects.length > 0;
  }

}
