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

package org.xipki.security.pkcs11;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.common.InvalidConfException;
import org.xipki.common.util.IoUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.password.PasswordResolver;
import org.xipki.security.exception.P11TokenException;
import org.xipki.security.exception.XiSecurityException;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class P11CryptServiceFactoryImpl implements P11CryptServiceFactory {

  private static final Logger LOG = LoggerFactory.getLogger(P11CryptServiceFactoryImpl.class);

  private static final Map<String, P11CryptService> services = new HashMap<>();

  private PasswordResolver passwordResolver;

  private P11Conf p11Conf;

  private String pkcs11ConfFile;

  private P11ModuleFactoryRegister p11ModuleFactoryRegister;

  public synchronized void init() throws InvalidConfException, IOException {
    if (p11Conf != null) {
      return;
    }
    if (StringUtil.isBlank(pkcs11ConfFile)) {
      LOG.error("no pkcs11ConfFile is configured, could not initialize");
      return;
    }

    this.p11Conf = new P11Conf(new FileInputStream(pkcs11ConfFile), passwordResolver);
  }

  public void setP11ModuleFactoryRegister(P11ModuleFactoryRegister p11ModuleFactoryRegister) {
    this.p11ModuleFactoryRegister = p11ModuleFactoryRegister;
  }

  public synchronized P11CryptService getP11CryptService(String moduleName)
      throws XiSecurityException, P11TokenException {
    if (p11Conf == null) {
      throw new IllegalStateException("please set pkcs11ConfFile and then call init() first");
    }

    final String name = getModuleName(moduleName);
    P11ModuleConf conf = p11Conf.getModuleConf(name);
    if (conf == null) {
      throw new XiSecurityException("PKCS#11 module " + name + " is not defined");
    }

    P11CryptService instance = services.get(moduleName);
    if (instance == null) {
      P11Module p11Module = p11ModuleFactoryRegister.getP11Module(conf);
      instance = new P11CryptService(p11Module);
      services.put(moduleName, instance);
    }

    return instance;
  }

  private String getModuleName(String moduleName) {
    return (moduleName == null) ? DEFAULT_P11MODULE_NAME : moduleName;
  }

  public void setPkcs11ConfFile(String confFile) {
    if (StringUtil.isBlank(confFile)) {
      this.pkcs11ConfFile = null;
    } else {
      this.pkcs11ConfFile = IoUtil.expandFilepath(confFile);
    }
  }

  public void setPasswordResolver(PasswordResolver passwordResolver) {
    this.passwordResolver = passwordResolver;
  }

  public void shutdown() {
    services.clear();
  }

  @Override
  public Set<String> getModuleNames() {
    if (p11Conf == null) {
      throw new IllegalStateException("pkcs11ConfFile is not set");
    }
    return p11Conf.getModuleNames();
  }

}
