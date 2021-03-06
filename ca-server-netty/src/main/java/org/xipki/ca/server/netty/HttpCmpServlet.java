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

package org.xipki.ca.server.netty;

import java.io.EOFException;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.SSLSession;

import org.bouncycastle.asn1.cmp.PKIMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.AuditEvent;
import org.xipki.audit.AuditLevel;
import org.xipki.audit.AuditService;
import org.xipki.audit.AuditServiceRegister;
import org.xipki.audit.AuditStatus;
import org.xipki.ca.api.RequestType;
import org.xipki.ca.server.api.CaAuditConstants;
import org.xipki.ca.server.api.ResponderManager;
import org.xipki.ca.server.api.X509CaCmpResponder;
import org.xipki.common.util.LogUtil;
import org.xipki.http.servlet.AbstractHttpServlet;
import org.xipki.http.servlet.ServletURI;
import org.xipki.http.servlet.SslReverseProxyMode;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class HttpCmpServlet extends AbstractHttpServlet {

  private static final Logger LOG = LoggerFactory.getLogger(HttpCmpServlet.class);

  private static final String CT_REQUEST = "application/pkixcmp";

  private static final String CT_RESPONSE = "application/pkixcmp";

  private ResponderManager responderManager;

  private AuditServiceRegister auditServiceRegister;

  public HttpCmpServlet() {
  }

  @Override
  public boolean needsTlsSessionInfo() {
    return true;
  }

  @Override
  public FullHttpResponse service(FullHttpRequest request, ServletURI servletUri,
      SSLSession sslSession, SslReverseProxyMode sslReverseProxyMode) throws Exception {
    HttpVersion httpVersion = request.protocolVersion();
    HttpMethod method = request.method();
    if (method != HttpMethod.POST) {
      return createErrorResponse(httpVersion, HttpResponseStatus.METHOD_NOT_ALLOWED);
    }

    X509Certificate clientCert = getClientCert(request, sslSession, sslReverseProxyMode);
    AuditService auditService = auditServiceRegister.getAuditService();
    AuditEvent event = new AuditEvent(new Date());
    event.setApplicationName(CaAuditConstants.APPNAME);
    event.setName(CaAuditConstants.NAME_PERF);
    event.addEventData(CaAuditConstants.NAME_reqType, RequestType.CMP.name());

    AuditLevel auditLevel = AuditLevel.INFO;
    AuditStatus auditStatus = AuditStatus.SUCCESSFUL;
    String auditMessage = null;
    try {
      if (responderManager == null) {
        String message = "responderManager in servlet not configured";
        LOG.error(message);
        throw new HttpRespAuditException(HttpResponseStatus.INTERNAL_SERVER_ERROR,
            message, AuditLevel.ERROR, AuditStatus.FAILED);
      }

      String reqContentType = request.headers().get("Content-Type");
      if (!CT_REQUEST.equalsIgnoreCase(reqContentType)) {
        String message = "unsupported media type " + reqContentType;
        throw new HttpRespAuditException(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
            message, AuditLevel.INFO, AuditStatus.FAILED);
      }

      String caName = null;
      X509CaCmpResponder responder = null;
      if (servletUri.getPath().length() > 1) {
        // skip the first char which is always '/'
        String caAlias = servletUri.getPath().substring(1);
        caName = responderManager.getCaNameForAlias(caAlias);
        if (caName == null) {
          caName = caAlias.toLowerCase();
        }
        responder = responderManager.getX509CaResponder(caName);
      }

      if (caName == null || responder == null || !responder.isOnService()) {
        String message;
        if (caName == null) {
          message = "no CA is specified";
        } else if (responder == null) {
          message = "unknown CA '" + caName + "'";
        } else {
          message = "CA '" + caName + "' is out of service";
        }
        LOG.warn(message);
        throw new HttpRespAuditException(HttpResponseStatus.NOT_FOUND, message,
            AuditLevel.INFO, AuditStatus.FAILED);
      }

      event.addEventData(CaAuditConstants.NAME_ca, responder.getCaName());

      byte[] reqContent = readContent(request);
      PKIMessage pkiReq;
      try {
        pkiReq = PKIMessage.getInstance(reqContent);
      } catch (Exception ex) {
        LogUtil.error(LOG, ex, "could not parse the request (PKIMessage)");
        throw new HttpRespAuditException(HttpResponseStatus.BAD_REQUEST,
            "bad request", AuditLevel.INFO, AuditStatus.FAILED);
      }

      PKIMessage pkiResp = responder.processPkiMessage(pkiReq, clientCert, event);
      byte[] encodedPkiResp = pkiResp.getEncoded();
      return createOKResponse(httpVersion, CT_RESPONSE, encodedPkiResp);
    } catch (HttpRespAuditException ex) {
      auditStatus = ex.getAuditStatus();
      auditLevel = ex.getAuditLevel();
      auditMessage = ex.getAuditMessage();
      return createErrorResponse(httpVersion, ex.getHttpStatus());
    } catch (Throwable th) {
      if (th instanceof EOFException) {
        LogUtil.warn(LOG, th, "connection reset by peer");
      } else {
        LOG.error("Throwable thrown, this should not happen!", th);
      }
      auditLevel = AuditLevel.ERROR;
      auditStatus = AuditStatus.FAILED;
      auditMessage = "internal error";
      return createErrorResponse(httpVersion, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    } finally {
      audit(auditService, event, auditLevel, auditStatus, auditMessage);
    }
  } // method service

  public void setResponderManager(ResponderManager responderManager) {
    this.responderManager = responderManager;
  }

  public void setAuditServiceRegister(AuditServiceRegister auditServiceRegister) {
    this.auditServiceRegister = auditServiceRegister;
  }

  private static void audit(AuditService auditService, AuditEvent event,
      AuditLevel auditLevel, AuditStatus auditStatus, String auditMessage) {
    if (auditLevel != null) {
      event.setLevel(auditLevel);
    }

    if (auditStatus != null) {
      event.setStatus(auditStatus);
    }

    if (auditMessage != null) {
      event.addEventData(CaAuditConstants.NAME_message, auditMessage);
    }

    event.finish();
    auditService.logEvent(event);
  } // method audit

}
