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

import org.xipki.security.exception.P11TokenException;
import org.xipki.security.pkcs11.P11Module;
import org.xipki.security.pkcs11.P11ModuleConf;
import org.xipki.security.pkcs11.P11ModuleFactory;

/**
 * TODO.
 * @author Lijun Liao
 *
 */
public class IaikP11ModuleFactory implements P11ModuleFactory {

  public IaikP11ModuleFactory() {
  }

  @Override
  public boolean canCreateModule(String type) {
    return IaikP11Module.TYPE.equalsIgnoreCase(type);
  }

  @Override
  public P11Module newModule(P11ModuleConf conf) throws P11TokenException {
    return IaikP11Module.getInstance(conf);
  }

}
