/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.pool.core;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

/**
 * Wrapper around a driver {@link PreparedStatement}: the parameterless {@code execute*} calls mark the connection
 * like the ones {@link PooledStatement} inherits. Every parameter setter is a passthrough.
 */
final class PooledPreparedStatement extends PooledStatement<PreparedStatement> implements PreparedStatement {

    PooledPreparedStatement(PreparedStatement delegate, PooledConnection connection) {
        super(delegate, connection);
    }

    /* ---- execution: work may be pending from here on ---- */

    @Override public ResultSet executeQuery() throws SQLException                               { connection.markWork(); return delegate.executeQuery(); }
    @Override public int       executeUpdate() throws SQLException                              { connection.markWork(); return delegate.executeUpdate(); }
    @Override public long      executeLargeUpdate() throws SQLException                         { connection.markWork(); return delegate.executeLargeUpdate(); }
    @Override public boolean   execute() throws SQLException                                    { connection.markWork(); return delegate.execute(); }

    /* ---- passthrough ---- */

    @Override public void addBatch() throws SQLException                                        { delegate.addBatch(); }
    @Override public void clearParameters() throws SQLException                                 { delegate.clearParameters(); }
    @Override public ResultSetMetaData getMetaData() throws SQLException                        { return delegate.getMetaData(); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException               { return delegate.getParameterMetaData(); }

    @Override public void setNull(int i, int sqlType) throws SQLException                       { delegate.setNull(i, sqlType); }
    @Override public void setNull(int i, int sqlType, String typeName) throws SQLException      { delegate.setNull(i, sqlType, typeName); }
    @Override public void setBoolean(int i, boolean x) throws SQLException                      { delegate.setBoolean(i, x); }
    @Override public void setByte(int i, byte x) throws SQLException                            { delegate.setByte(i, x); }
    @Override public void setShort(int i, short x) throws SQLException                          { delegate.setShort(i, x); }
    @Override public void setInt(int i, int x) throws SQLException                              { delegate.setInt(i, x); }
    @Override public void setLong(int i, long x) throws SQLException                            { delegate.setLong(i, x); }
    @Override public void setFloat(int i, float x) throws SQLException                          { delegate.setFloat(i, x); }
    @Override public void setDouble(int i, double x) throws SQLException                        { delegate.setDouble(i, x); }
    @Override public void setBigDecimal(int i, BigDecimal x) throws SQLException                { delegate.setBigDecimal(i, x); }
    @Override public void setString(int i, String x) throws SQLException                        { delegate.setString(i, x); }
    @Override public void setNString(int i, String x) throws SQLException                       { delegate.setNString(i, x); }
    @Override public void setBytes(int i, byte[] x) throws SQLException                         { delegate.setBytes(i, x); }
    @Override public void setDate(int i, Date x) throws SQLException                            { delegate.setDate(i, x); }
    @Override public void setDate(int i, Date x, Calendar cal) throws SQLException              { delegate.setDate(i, x, cal); }
    @Override public void setTime(int i, Time x) throws SQLException                            { delegate.setTime(i, x); }
    @Override public void setTime(int i, Time x, Calendar cal) throws SQLException              { delegate.setTime(i, x, cal); }
    @Override public void setTimestamp(int i, Timestamp x) throws SQLException                  { delegate.setTimestamp(i, x); }
    @Override public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException    { delegate.setTimestamp(i, x, cal); }
    @Override public void setObject(int i, Object x) throws SQLException                        { delegate.setObject(i, x); }
    @Override public void setObject(int i, Object x, int sqlType) throws SQLException           { delegate.setObject(i, x, sqlType); }
    @Override public void setObject(int i, Object x, int sqlType, int scaleOrLength) throws SQLException
                                                                                                { delegate.setObject(i, x, sqlType, scaleOrLength); }
    @Override public void setObject(int i, Object x, SQLType sqlType) throws SQLException       { delegate.setObject(i, x, sqlType); }
    @Override public void setObject(int i, Object x, SQLType sqlType, int scaleOrLength) throws SQLException
                                                                                                { delegate.setObject(i, x, sqlType, scaleOrLength); }
    @Override public void setRef(int i, Ref x) throws SQLException                              { delegate.setRef(i, x); }
    @Override public void setArray(int i, Array x) throws SQLException                          { delegate.setArray(i, x); }
    @Override public void setURL(int i, URL x) throws SQLException                              { delegate.setURL(i, x); }
    @Override public void setRowId(int i, RowId x) throws SQLException                          { delegate.setRowId(i, x); }
    @Override public void setSQLXML(int i, SQLXML x) throws SQLException                        { delegate.setSQLXML(i, x); }

    /* ---- LOB / stream parameters (passthrough) ---- */

    @Override public void setBlob(int i, Blob x) throws SQLException                            { delegate.setBlob(i, x); }
    @Override public void setBlob(int i, InputStream x) throws SQLException                     { delegate.setBlob(i, x); }
    @Override public void setBlob(int i, InputStream x, long length) throws SQLException        { delegate.setBlob(i, x, length); }
    @Override public void setClob(int i, Clob x) throws SQLException                            { delegate.setClob(i, x); }
    @Override public void setClob(int i, Reader x) throws SQLException                          { delegate.setClob(i, x); }
    @Override public void setClob(int i, Reader x, long length) throws SQLException             { delegate.setClob(i, x, length); }
    @Override public void setNClob(int i, NClob x) throws SQLException                          { delegate.setNClob(i, x); }
    @Override public void setNClob(int i, Reader x) throws SQLException                         { delegate.setNClob(i, x); }
    @Override public void setNClob(int i, Reader x, long length) throws SQLException            { delegate.setNClob(i, x, length); }
    @Override public void setAsciiStream(int i, InputStream x) throws SQLException              { delegate.setAsciiStream(i, x); }
    @Override public void setAsciiStream(int i, InputStream x, int length) throws SQLException  { delegate.setAsciiStream(i, x, length); }
    @Override public void setAsciiStream(int i, InputStream x, long length) throws SQLException { delegate.setAsciiStream(i, x, length); }
    @Override public void setBinaryStream(int i, InputStream x) throws SQLException             { delegate.setBinaryStream(i, x); }
    @Override public void setBinaryStream(int i, InputStream x, int length) throws SQLException { delegate.setBinaryStream(i, x, length); }
    @Override public void setBinaryStream(int i, InputStream x, long length) throws SQLException
                                                                                                { delegate.setBinaryStream(i, x, length); }
    @Override public void setCharacterStream(int i, Reader x) throws SQLException               { delegate.setCharacterStream(i, x); }
    @Override public void setCharacterStream(int i, Reader x, int length) throws SQLException   { delegate.setCharacterStream(i, x, length); }
    @Override public void setCharacterStream(int i, Reader x, long length) throws SQLException  { delegate.setCharacterStream(i, x, length); }
    @Override public void setNCharacterStream(int i, Reader x) throws SQLException              { delegate.setNCharacterStream(i, x); }
    @Override public void setNCharacterStream(int i, Reader x, long length) throws SQLException { delegate.setNCharacterStream(i, x, length); }

    @Override
    @SuppressWarnings("deprecation")
    public void setUnicodeStream(int i, InputStream x, int length) throws SQLException {
        delegate.setUnicodeStream(i, x, length);
    }
}
