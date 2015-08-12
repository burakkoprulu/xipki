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

package org.xipki.datasource.api;

/**
 * @author Lijun Liao
 */

public enum DatabaseType
{
    H2,
    DB2,
    HSQL,
    MYSQL,
    ORACLE,
    POSTGRES,
    UNKNOWN;

    public static DatabaseType getDataSourceForDriver(
            final String driverClass)
    {
        return getDatabaseType(driverClass);
    }

    public static DatabaseType getDataSourceForDataSource(
            final String dataSourceClass)
    {
        return getDatabaseType(dataSourceClass);
    }

    private static DatabaseType getDatabaseType(
            final String className)
    {
        if(className.contains("db2."))
        {
            return DatabaseType.DB2;
        }
        if(className.contains("h2."))
        {
            return DatabaseType.H2;
        }
        else if(className.contains("hsqldb."))
        {
            return DatabaseType.HSQL;
        }
        else if(className.contains("mysql."))
        {
            return DatabaseType.MYSQL;
        }
        else if(className.contains("oracle."))
        {
            return DatabaseType.ORACLE;
        }
        else if(className.contains("postgres.") || className.contains("postgresql."))
        {
            return DatabaseType.POSTGRES;
        }
        else
        {
            return DatabaseType.UNKNOWN;
        }
    }

}