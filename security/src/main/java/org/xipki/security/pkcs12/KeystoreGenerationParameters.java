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

package org.xipki.security.pkcs12;

import java.security.SecureRandom;

import org.xipki.common.util.ParamUtil;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.2.0
 */

public class KeystoreGenerationParameters {

  private final char[] password;

  private SecureRandom random;

  public KeystoreGenerationParameters(char[] password) {
    this.password = ParamUtil.requireNonNull("password", password);
  }

  public SecureRandom getRandom() {
    return random;
  }

  public void setRandom(SecureRandom random) {
    this.random = random;
  }

  public char[] getPassword() {
    return password;
  }

}
