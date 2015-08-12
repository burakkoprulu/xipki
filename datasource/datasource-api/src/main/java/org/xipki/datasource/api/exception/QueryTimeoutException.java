/*
 * Copyright (c) 2015 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 *
 */

package org.xipki.datasource.api.exception;

/**
 * Copied from Spring Framework licensed under Apache License, version 2.0.
 *
 * Exception to be thrown on a query timeout. This could have different causes depending on
 * the database API in use but most likely thrown after the database interrupts or stops
 * the processing of a query before it has completed.
 *
 * <p>This exception can be thrown by user code trapping the native database exception or
 * by exception translation.
 *
 * @author Thomas Risberg
 */
@SuppressWarnings("serial")
public class QueryTimeoutException extends TransientDataAccessException
{

    /**
     * Constructor for QueryTimeoutException.
     * @param msg the detail message
     */
    public QueryTimeoutException(
            final String msg)
    {
        super(msg);
    }

    /**
     * Constructor for QueryTimeoutException.
     * @param msg the detail message
     * @param cause the root cause from the data access API in use
     */
    public QueryTimeoutException(
            final String msg,
            final Throwable cause)
    {
        super(msg, cause);
    }

}