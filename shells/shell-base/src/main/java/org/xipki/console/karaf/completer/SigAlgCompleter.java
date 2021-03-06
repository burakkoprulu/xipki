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

package org.xipki.console.karaf.completer;

import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.xipki.console.karaf.AbstractEnumCompleter;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Service
public class SigAlgCompleter extends AbstractEnumCompleter {

  public SigAlgCompleter() {
    String[] encAlgs = {"RSA", "RSAandMGF1", "ECDSA", "DSA"};
    String[] hashAlgs = {"SHA1", "SHA224", "SHA256", "SHA384", "SHA512",
      "SHA3-224, SHA3-256, SHA3-384, SHA3-512"};
    StringBuilder enums = new StringBuilder(200);
    for (String encAlg : encAlgs) {
      for (String hashAlg : hashAlgs) {
        enums.append(hashAlg).append("with").append(encAlg).append(",");
      }
    }

    hashAlgs = new String[]{"SHA1", "SHA224", "SHA256", "SHA384", "SHA512"};
    for (String hashAlg : hashAlgs) {
      enums.append(hashAlg).append("withPlainECDSA,");
    }

    enums.append("SM3withSM2");
    setTokens(enums.toString());
  }

}
