/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.storage.dbsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.inject.Provider;
import javax.inject.Singleton;
import javax.sql.DataSource;

import org.rapla.RaplaResources;
import org.rapla.components.util.DateTools;
import org.rapla.components.util.xml.RaplaNonValidatedInput;
import org.rapla.entities.Category;
import org.rapla.entities.Entity;
import org.rapla.entities.User;
import org.rapla.entities.domain.permission.PermissionExtension;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.extensionpoints.FunctionFactory;
import org.rapla.entities.internal.CategoryImpl;
import org.rapla.entities.internal.ModifiableTimestamp;
import org.rapla.entities.storage.ImportExportEntity;
import org.rapla.entities.storage.RefEntity;
import org.rapla.entities.storage.ReferenceInfo;
import org.rapla.facade.Conflict;
import org.rapla.facade.RaplaComponent;
import org.rapla.framework.RaplaException;
import org.rapla.framework.RaplaLocale;
import org.rapla.framework.internal.ConfigTools;
import org.rapla.logger.Logger;
import org.rapla.scheduler.Command;
import org.rapla.scheduler.CommandScheduler;
import org.rapla.storage.CachableStorageOperator;
import org.rapla.storage.CachableStorageOperatorCommand;
import org.rapla.storage.IdCreator;
import org.rapla.storage.ImportExportManager;
import org.rapla.storage.LocalCache;
import org.rapla.storage.PreferencePatch;
import org.rapla.storage.UpdateEvent;
import org.rapla.storage.impl.EntityStore;
import org.rapla.storage.impl.server.EntityHistory;
import org.rapla.storage.impl.server.EntityHistory.HistoryEntry;
import org.rapla.storage.impl.server.LocalAbstractCachableOperator;
import org.rapla.storage.xml.IOContext;
import org.rapla.storage.xml.RaplaDefaultXMLContext;

/** This Operator is used to store the data in a SQL-DBMS.*/
@Singleton public class DBOperator extends LocalAbstractCachableOperator
{
    //protected String datasourceName;
    Properties dbProperties = new Properties();
    boolean bSupportsTransactions = false;
    boolean hsqldb = false;

    //private String backupEncoding;
    //private String backupFileName;

    DataSource lookup;

    private String connectionName;
    Provider<ImportExportManager> importExportManager;

    public DBOperator(Logger logger, RaplaResources i18n, RaplaLocale locale, final CommandScheduler scheduler, Map<String, FunctionFactory> functionFactoryMap,
            Provider<ImportExportManager> importExportManager, DataSource dataSource, Set<PermissionExtension> permissionExtensions)
    {
        super(logger, i18n, locale, scheduler, functionFactoryMap, permissionExtensions);
        lookup = dataSource;
        this.importExportManager = importExportManager;
        //        String backupFile = config.getChild("backup").getValue("");
        //        if (backupFile != null)
        //        	backupFileName = ContextTools.resolveContext( backupFile, context);
        //
        //        backupEncoding = config.getChild( "encoding" ).getValue( "utf-8" );

        //        datasourceName = config.getChild("datasource").getValue(null);
        //        // dont use datasource (we have to configure a driver )
        //        if ( datasourceName == null)
        //        {
        //            throw new RaplaException("Could not instantiate DB. Datasource not configured ");
        //        }
        //        else
        //        {
        //	        try {
        //	        	lookupDeprecated  = ContextTools.resolveContextObject(datasourceName, context );
        //	        } catch (RaplaXMLContextException ex) {
        //	        	throw new RaplaDBExceptionc("Datasource " + datasourceName + " not found");
        //	        }
        //        }

    }

    private void scheduleCleanupAndRefresh()
    {
        {
            final int delay = 30000;
            final int period = 15000;
            scheduleConnectedTasks(new Command()
            {
                @Override
                public void execute() throws Exception
                {
                    try (final Connection connection = createConnection())
                    {
                        final RaplaDefaultXMLContext context = createOutputContext(cache);
                        final RaplaSQL raplaSQL = new RaplaSQL(context);
                        raplaSQL.cleanupOldLocks(connection);
                        connection.commit();
                    }
                    catch (Throwable t)
                    {
                        DBOperator.this.logger.info("Could not release old locks");
                    }
                }
            }, delay, period);
        }
        {
            long delay = 100;//DateTools.MILLISECONDS_PER_DAY;
            long period = DateTools.MILLISECONDS_PER_DAY;
            scheduleConnectedTasks(() -> {
                try(final Connection con = createConnection())
                {
                    final RaplaDefaultXMLContext context = createOutputContext(cache);
                    final RaplaSQL raplaSQL = new RaplaSQL(context);
                    final Date date = new Date(getLastRefreshed().getTime() - LocalAbstractCachableOperator.HISTORY_DURATION);
                    raplaSQL.cleanupHistory(con, date);
                    con.commit();
                }
                catch(Throwable t)
                {
                    DBOperator.this.logger.info("could not clean up history: "+t.getMessage());
                }
            }, delay, period);
        }
    }

    public boolean supportsActiveMonitoring()
    {
        return true;
    }

    public String getConnectionName()
    {
        if (connectionName != null)
        {
            return connectionName;
        }
        //    	if ( datasourceName != null)
        //    	{
        //    	    return datasourceName;
        //    	}
        return "not configured";
    }

    public Connection createConnection() throws RaplaException
    {
        boolean withTransactionSupport = true;
        return createConnection(withTransactionSupport);
    }

    public ImportExportManager getImportExportManager()
    {
        return importExportManager.get();
    }

    public Connection createConnection(boolean withTransactionSupport) throws RaplaException
    {
        return createConnection(withTransactionSupport, 0);
    }

    private Connection createConnection(final boolean withTransactionSupport, final int count) throws RaplaException
    {
        Connection connection = null;
        try
        {
            //datasource lookupDeprecated
            Object source = lookup;
            //        	if ( lookupDeprecated instanceof String)
            //        	{
            //        		InitialContext ctx = new InitialContext();
            //        		source  = ctx.lookupDeprecated("java:comp/env/"+ lookupDeprecated);
            //        	}
            //        	else
            //        	{
            //        		source = lookupDeprecated;
            //        	}

            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            try
            {
                try
                {
                    Thread.currentThread().setContextClassLoader(source.getClass().getClassLoader());
                }
                catch (Exception ex)
                {
                }
                try
                {
                    DataSource ds = (DataSource) source;
                    connection = ds.getConnection();
                }
                catch (ClassCastException ex)
                {
                    String text = "Datasource object " + source.getClass() + " does not implement a datasource interface.";
                    getLogger().error(text);
                    throw new RaplaDBException(text);
                }
            }
            finally
            {
                try
                {
                    Thread.currentThread().setContextClassLoader(contextClassLoader);
                }
                catch (Exception ex)
                {
                }
            }
            if (withTransactionSupport)
            {
                bSupportsTransactions = connection.getMetaData().supportsTransactions();
                if (bSupportsTransactions)
                {
                    connection.setAutoCommit(false);
                }
                else
                {
                    getLogger().warn("No Transaction support");
                }
            }
            else
            {
                connection.setAutoCommit(true);
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            //connection.createStatement().execute( "ALTER TABLE RESOURCE RENAME TO RAPLA_RESOURCE");
            // 		     connection.commit();
            return connection;
        }
        catch (Throwable ex)
        {
            if (connection != null)
            {
                close(connection);
            }
            if (ex instanceof SQLException && count < 2)
            {
                getLogger().warn("Getting error " + ex.getMessage() + ". Retrying.");
                return createConnection(withTransactionSupport, count + 1);
            }
            if (ex instanceof RaplaDBException)
            {
                throw (RaplaDBException) ex;
            }
            throw new RaplaDBException("DB-Connection aborted", ex);
        }
    }

    @Override synchronized public void connect() throws RaplaException
    {
        if (!isConnected())
        {
            getLogger().debug("Connecting: " + getConnectionName());
            loadData();
            changeStatus(InitStatus.Loaded);
            initIndizes();
            changeStatus(InitStatus.Connected);
            scheduleCleanupAndRefresh();
        }
        /*
        if (connectInfo != null)
        {
            final String username = connectInfo.getUsername();
            final UserImpl user = cache.getUser(username);
            if (user == null)
            {
                throw new RaplaSecurityException("User " + username + " not found!");
            }
            return user;
        }
        else
        {
            return null;
        }*/
    }

    @Override protected void refreshWithoutLock()
    {
        if (!isConnected())
        {
            return;
        }
        try (Connection c = createConnection())
        {
            refreshWithoutLock(c);
        }
        catch (Throwable e)
        {
            Date lastUpdated = getLastRefreshed();
            logger.error("Error updating model from DB. Last success was at " + lastUpdated, e);
        }
    }

    private void refreshWithoutLock(Connection c) throws SQLException, RaplaException
    {
        final EntityStore entityStore = new EntityStore(cache);
        final Category superCategory = cache.getSuperCategory();
        final RaplaSQL raplaSQLInput = new RaplaSQL(createInputContext(entityStore, DBOperator.this, superCategory));
        Date lastUpdated = getLastRefreshed();
        Date connectionTime = raplaSQLInput.getLastUpdated(c);

        if (connectionTime.before(lastUpdated))
        {
            return;
        }
        final Collection<ReferenceInfo> allIds = raplaSQLInput.update(c, lastUpdated, connectionTime);
        Collection<Entity> toStore = new LinkedHashSet<Entity>();
        Set<ReferenceInfo> toRemove = new HashSet<>();
        List<PreferencePatch> patches = raplaSQLInput.getPatches(c, lastUpdated);
        for (ReferenceInfo id : allIds)
        {
            final HistoryEntry before = history.getLatest(id);//LastChangedUntil(id, connectionTime);
            if (before.isDelete())
            {
                toRemove.add(before.getId());
            }
            else
            {
                final Entity entity = history.getEntity(before);
                setResolver(Collections.singleton(entity));
                toStore.add(entity);
            }
        }
        refresh(lastUpdated, connectionTime, toStore, patches, toRemove);
        return;
    }

    @Override
    synchronized public void disconnect() throws RaplaException
    {
        super.disconnect();

        // HSQLDB Special
        if (hsqldb)
        {
            String sql = "SHUTDOWN COMPACT";
            try
            {
                getLogger().info("Disconnecting: " + getConnectionName());
                Connection connection = createConnection();
                Statement statement = connection.createStatement();
                statement.execute(sql);
                statement.close();
            }
            catch (SQLException ex)
            {
                throw new RaplaException(ex);
            }
        }
    }

    public final void loadData() throws RaplaException
    {

        Connection c = null;
        final Lock writeLock = RaplaComponent.lock(this.lock.writeLock(), 10);
        try
        {
            c = createConnection();
            connectionName = c.getMetaData().getURL();
            getLogger().info("Using datasource " + c.getMetaData().getDatabaseProductName() + ": " + connectionName);
            if (upgradeDatabase(c))
            {
                close(c);
                c = null;
                c = createConnection();
            }
            cache.clearAll();
            addInternalTypes(cache);
            loadData(c, cache);

            if (getLogger().isDebugEnabled())
                getLogger().debug("Entities contextualized");

            if (getLogger().isDebugEnabled())
                getLogger().debug("All ConfigurationReferences resolved");
        }
        catch (RaplaException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new RaplaException(ex);
        }
        finally
        {
            unlock(writeLock);
            close(c);
            c = null;
        }
    }

    @SuppressWarnings("deprecation") private boolean upgradeDatabase(Connection c) throws SQLException, RaplaException
    {
        Map<String, TableDef> schema = loadDBSchema(c);
        TableDef dynamicTypeDef = schema.get("DYNAMIC_TYPE");
        boolean empty = false;
        int oldIdColumnCount = 0;
        int unpatchedTables = 0;

        if (dynamicTypeDef != null)
        {
            PreparedStatement prepareStatement = null;
            ResultSet set = null;
            try
            {
                prepareStatement = c.prepareStatement("select * from DYNAMIC_TYPE");
                set = prepareStatement.executeQuery();
                empty = !set.next();
            }
            finally
            {
                if (set != null)
                {
                    set.close();
                }
                if (prepareStatement != null)
                {
                    prepareStatement.close();
                }
            }

            {
                org.rapla.storage.dbsql.RaplaSQL raplaSQLOutput = new org.rapla.storage.dbsql.RaplaSQL(createOutputContext(cache));
                Map<String, String> idColumnMap = raplaSQLOutput.getIdColumns();
                oldIdColumnCount = idColumnMap.size();
                for (Map.Entry<String, String> entry : idColumnMap.entrySet())
                {
                    String table = entry.getKey();
                    String idColumnName = entry.getValue();
                    TableDef tableDef = schema.get(table);
                    if (tableDef != null)
                    {
                        ColumnDef idColumn = tableDef.getColumn(idColumnName);
                        if (idColumn == null)
                        {
                            throw new RaplaException("Id column not found");
                        }
                        if (idColumn.isIntType())
                        {
                            unpatchedTables++;
                            //                        else if ( type.toLowerCase().contains("varchar"))
                            //                        {
                            //                            patchedTables++;
                            //                        }
                        }
                    }
                }
            }
        }
        else
        {
            empty = true;
        }
        if (!empty && (unpatchedTables == oldIdColumnCount && unpatchedTables > 0))
        {
            final String message = "Old database schema detected. Please export data.xml with 1.8 rapla version and import in new !";
            getLogger().error(message);
            throw new RaplaException(message);
            //close( c);
        }

        if (empty || unpatchedTables > 0)
        {
            ImportExportManager manager = importExportManager.get();
            CachableStorageOperator sourceOperator = manager.getSource();
            if (sourceOperator == this)
            {
                throw new RaplaException("Can't import, because db is configured as source.");
            }
            if (unpatchedTables > 0)
            {
                getLogger().info("Reading data from xml.");
            }
            else
            {
                getLogger().warn("Empty database. Importing data from " + sourceOperator);
            }
            sourceOperator.connect();
            if (unpatchedTables > 0)
            {
                org.rapla.storage.dbsql.RaplaSQL raplaSQLOutput = new org.rapla.storage.dbsql.RaplaSQL(createOutputContext(cache));
                getLogger().warn("Dropping database tables and reimport from " + sourceOperator);
                raplaSQLOutput.removeAll(c);
                // we need to load the new schema after dropping
                schema = loadDBSchema(c);
            }
            {
                RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
                raplaSQLOutput.createOrUpdateIfNecessary(c, schema);
            }
            close(c);
            c = null;
            c = createConnection();
            final Connection conn = c;
            sourceOperator.runWithReadLock(new CachableStorageOperatorCommand()
            {

                @Override public void execute(LocalCache cache) throws RaplaException
                {
                    try
                    {
                        saveData(conn, cache);
                    }
                    catch (SQLException ex)
                    {
                        throw new RaplaException(ex.getMessage(), ex);
                    }
                }
            });
            return true;
        }
        else
        {
            // Normal Database upgrade
            RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
            raplaSQLOutput.createOrUpdateIfNecessary(c, schema);
        }
        return false;
    }



    private Map<String, TableDef> loadDBSchema(Connection c) throws SQLException
    {
        Map<String, TableDef> tableMap = new LinkedHashMap<String, TableDef>();
        List<String> catalogList = new ArrayList<String>();
        DatabaseMetaData metaData = c.getMetaData();
        {
            ResultSet set = metaData.getCatalogs();
            try
            {
                while (set.next())
                {
                    String name = set.getString("TABLE_CAT");
                    catalogList.add(name);
                }
            }
            finally
            {
                set.close();
            }
        }
        List<String> schemaList = new ArrayList<String>();
        {
            ResultSet set = metaData.getSchemas();
            try
            {
                while (set.next())
                {
                    String name = set.getString("TABLE_SCHEM");
                    String cat = set.getString("TABLE_CATALOG");
                    schemaList.add(name);
                    if (cat != null)
                    {
                        catalogList.add(name);
                    }
                }
            }
            finally
            {
                set.close();
            }
        }

        if (catalogList.isEmpty())
        {
            catalogList.add(null);
        }
        Map<String, Set<String>> tables = new LinkedHashMap<String, Set<String>>();
        for (String cat : catalogList)
        {
            LinkedHashSet<String> tableSet = new LinkedHashSet<String>();
            String[] types = new String[] { "TABLE" };
            tables.put(cat, tableSet);
            {
                ResultSet set = metaData.getTables(cat, null, null, types);
                try
                {
                    while (set.next())
                    {
                        String name = set.getString("TABLE_NAME");
                        tableSet.add(name);
                    }
                }
                finally
                {
                    set.close();
                }
            }
        }
        for (String cat : catalogList)
        {
            Set<String> tableNameSet = tables.get(cat);
            for (String tableName : tableNameSet)
            {
                ResultSet set = metaData.getColumns(null, null, tableName, null);
                try
                {
                    while (set.next())
                    {
                        String table = set.getString("TABLE_NAME").toUpperCase(Locale.ENGLISH);
                        TableDef tableDef = tableMap.get(table);
                        if (tableDef == null)
                        {
                            tableDef = new TableDef(table);
                            tableMap.put(table, tableDef);
                        }
                        ColumnDef columnDef = new ColumnDef(set);
                        tableDef.addColumn(columnDef);
                    }
                }
                finally
                {
                    set.close();
                }
            }
        }
        return tableMap;
    }

    public void dispatch(UpdateEvent evt) throws RaplaException
    {
        Lock writeLock = writeLock();
        try
        {
            //Date since = lastUpdated;
            preprocessEventStorage(evt);
            Collection<Entity> storeObjects = evt.getStoreObjects();
            List<PreferencePatch> preferencePatches = evt.getPreferencePatches();
            Collection<ReferenceInfo> removeObjects = evt.getRemoveIds();
            if (storeObjects.isEmpty() && preferencePatches.isEmpty() && removeObjects.isEmpty())
            {
                return;
            }
            Connection connection = createConnection();
            try
            {
                dbStore(storeObjects, preferencePatches, removeObjects, connection, evt.getUserId());
                try
                {
                    refreshWithoutLock(connection);
                }
                catch (SQLException e)
                {
                    getLogger().error("Could not load update from db. Will be loaded afterwards", e);
                }
            }
            finally
            {
                close(connection);
            }
        }
        finally
        {
            unlock(writeLock);
        }
        // TODO check if still needed
        //fireStorageUpdated(result);
    }

    private void dbStore(Collection<Entity> storeObjects, List<PreferencePatch> preferencePatches, Collection<ReferenceInfo> removeObjects,
            Connection connection, String userId) throws RaplaException
    {
        final LinkedHashSet<ReferenceInfo> ids = new LinkedHashSet<ReferenceInfo>();
        for (Entity entity : storeObjects)
        {
            ids.add(entity.getReference());
        }
        for (ReferenceInfo referenceInfo : removeObjects)
        {
            ids.add(referenceInfo);
        }
        for (PreferencePatch patch : preferencePatches)
        {
            ids.add(patch.getReference());
        }
        final boolean needsGlobalLock = containsDynamicType(ids);
        Date connectionTimestamp = null;
        final Collection<String> lockIds = needsGlobalLock ? Collections.singletonList(LockStorage.GLOBAL_LOCK) : getLockIds(ids);
        RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
        try
        {
            connectionTimestamp = raplaSQLOutput.getDatabaseTimestamp(connection);
            User lastChangedBy = (userId != null) ? resolve(userId, User.class) : null;
            for (Entity e : storeObjects)
            {
                if (e instanceof ModifiableTimestamp)
                {
                    ModifiableTimestamp modifiableTimestamp = (ModifiableTimestamp) e;
                    if ( lastChangedBy != null)
                    {
                        modifiableTimestamp.setLastChangedBy(lastChangedBy);
                    }
                    final Entity entity = tryResolve(e.getReference());
                    if ( entity == null)
                    {
                        modifiableTimestamp.setCreateDate( connectionTimestamp );
                    }
                }
            }

            raplaSQLOutput.requestLocks(connection, connectionTimestamp, lockIds, null, !needsGlobalLock);
            for (ReferenceInfo id : removeObjects)
            {
                raplaSQLOutput.remove(connection, id, connectionTimestamp);
            }
            raplaSQLOutput.store(connection, storeObjects, connectionTimestamp);
            raplaSQLOutput.storePatches(connection, preferencePatches, connectionTimestamp);
            if (bSupportsTransactions)
            {
                getLogger().debug("Commiting");
                connection.commit();
            }
            //            refreshWithoutLock(connection);
        }
        catch (Exception ex)
        {
            try
            {
                if (bSupportsTransactions)
                {
                    connection.rollback();
                    getLogger().error("Doing rollback for: " + ex.getMessage());
                    throw new RaplaDBException(getI18n().getString("error.rollback"), ex);
                }
                else
                {
                    String message = getI18n().getString("error.no_rollback");
                    getLogger().error(message);
                    forceDisconnect();
                    throw new RaplaDBException(message, ex);
                }
            }
            catch (SQLException sqlEx)
            {
                String message = "Unrecoverable error while storing";
                getLogger().error(message, sqlEx);
                forceDisconnect();
                throw new RaplaDBException(message, sqlEx);
            }
        }
        finally
        {
            try
            {
                raplaSQLOutput.removeLocks(connection, lockIds, connectionTimestamp, !needsGlobalLock);
                if (bSupportsTransactions)
                {
                    connection.commit();
                }
            }
            catch (Exception ex)
            {
                getLogger().error("Could noe remove locks. They will be removed during next cleanup. ", ex);
            }
        }
    }

    private Collection<String> getLockIds(Collection<ReferenceInfo> ids)
    {
        List<String> result = new ArrayList<String>();
        for (ReferenceInfo info : ids)
        {
            result.add(info.getId());
        }
        return result;
    }

    private boolean containsDynamicType(Set<ReferenceInfo> ids)
    {
        for (ReferenceInfo id : ids)
        {
            final Entity entity = tryResolve(id);
            if (entity != null && entity.getTypeClass() == DynamicType.class)
            {
                return true;
            }
        }
        return false;
    }

    @Override protected void removeConflictsFromDatabase(Collection<ReferenceInfo<Conflict>> disabledConflicts)
    {
        super.removeConflictsFromDatabase(disabledConflicts);
        if (disabledConflicts.isEmpty())
        {
            return;
        }
        Collection<Entity> storeObjects = Collections.emptyList();
        List<PreferencePatch> preferencePatches = Collections.emptyList();
        Collection<ReferenceInfo> removeObjects = new ArrayList<ReferenceInfo>();
        for (ReferenceInfo<Conflict> id : disabledConflicts)
        {
            removeObjects.add(id);
        }
        try (Connection connection = createConnection())
        {
            dbStore(storeObjects, preferencePatches, removeObjects, connection, null);
        }
        catch (Exception ex)
        {
            getLogger().warn("disabled conflicts could not be removed from database due to ", ex);
        }

    }

    public void removeAll() throws RaplaException
    {
        Connection connection = createConnection();

        try
        {
            Map<String, TableDef> schema = loadDBSchema(connection);
            TableDef dynamicTypeDef = schema.get("DYNAMIC_TYPE");
            if (dynamicTypeDef != null)
            {
                RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
                raplaSQLOutput.removeAll(connection);
                connection.commit();
                // do something here
                getLogger().info("DB cleared");
            }
            else
            {
                getLogger().warn("DB is not created. Could not remove all");
            }
        }
        catch (SQLException ex)
        {
            throw new RaplaException(ex);
        }
        finally
        {
            close(connection);
        }
    }

    public synchronized void saveData(LocalCache cache, String version) throws RaplaException
    {
        Connection connection = createConnection();
        try
        {
            Map<String, TableDef> schema = loadDBSchema(connection);
            RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
            raplaSQLOutput.createOrUpdateIfNecessary(connection, schema);
            saveData(connection, cache);
        }
        catch (SQLException ex)
        {
            throw new RaplaException(ex);
        }
        finally
        {
            close(connection);
        }
    }

    protected void saveData(Connection connection, LocalCache cache) throws RaplaException, SQLException
    {
        String connectionName = getConnectionName();
        getLogger().info("Importing Data into " + connectionName);
        RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));

        //		if (dropOldTables)
        //		{
        //		    getLogger().info("Droping all data from " + connectionName);
        //            raplaSQLOutput.dropAndRecreate( connection );
        //		}
        //		else
        {
            getLogger().info("Deleting all old Data from " + connectionName);
            raplaSQLOutput.removeAll(connection);
        }
        getLogger().info("Inserting new Data into " + connectionName);
        raplaSQLOutput.createAll(connection);
        if (!connection.getAutoCommit())
        {
            connection.commit();
        }
        // do something here
        getLogger().info("Import complete for " + connectionName);
    }

    private void close(Connection connection)
    {

        if (connection == null)
        {
            return;
        }
        try
        {
            if (!connection.isClosed())
            {
                getLogger().debug("Closing " + connection);
                connection.close();
            }
        }
        catch (SQLException e)
        {
            getLogger().error("Can't close connection to database ", e);
        }
    }

    @Override public Date getHistoryValidStart()
    {
        final Date date = new Date(getLastRefreshed().getTime() - HISTORY_DURATION);
        return date;
    }

    private Date loadInitialLastUpdateFromDb(Connection connection) throws SQLException, RaplaException
    {
        final RaplaDefaultXMLContext createOutputContext = createOutputContext(cache);
        final RaplaSQL raplaSQL = new RaplaSQL(createOutputContext);
        final Date lastUpdated = raplaSQL.getLastUpdated(connection);
        return lastUpdated;
    }

    protected void loadData(Connection connection, LocalCache cache) throws RaplaException, SQLException
    {
        final Date lastUpdated = loadInitialLastUpdateFromDb(connection);
        setLastRefreshed(lastUpdated);
        setConnectStart(lastUpdated);
        EntityStore entityStore = new EntityStore(cache);
        CategoryImpl superCategory = new CategoryImpl();
        superCategory.setId(Category.SUPER_CATEGORY_REF);
        superCategory.setResolver(this);
        superCategory.setKey("supercategory");
        superCategory.getName().setName("en", "Root");
        entityStore.put( superCategory);
        final RaplaDefaultXMLContext inputContext = createInputContext(entityStore, this, superCategory);
        RaplaSQL raplaSQLInput = new RaplaSQL(inputContext);
        raplaSQLInput.loadAll(connection);
        final Collection<ReferenceInfo> entitiesToRemove = removeInconsistentReservations(entityStore);

        Collection<Entity> list = entityStore.getList();
        final HistoryEntry latest = history.getLatest(Category.SUPER_CATEGORY_REF);
        if ( latest != null)
        {
            final Category historyCategory = (Category)history.getEntity(latest);
            superCategory.setLastChanged( historyCategory.getLastChanged());
            superCategory.setCreateDate(historyCategory.getCreateDate());
        }
        cache.putAll(list);
        resolveInitial(list, this);
        removeInconsistentEntities(cache, list);
        Collection<Entity> migratedTemplates = migrateTemplates();
        cache.putAll(migratedTemplates);
        List<PreferencePatch> preferencePatches = Collections.emptyList();
        Collection<ReferenceInfo> removeObjects = (Collection)entitiesToRemove;
        dbStore(migratedTemplates, preferencePatches, removeObjects, connection, null);
        // It is important to do the read only later because some resolve might involve write to referenced objects
        for (Entity entity : list)
        {
            ((RefEntity) entity).setReadOnly();
        }
        for (Entity entity : migratedTemplates)
        {
            ((RefEntity) entity).setReadOnly();
        }
        cache.getSuperCategory().setReadOnly();
        for (User user : cache.getUsers())
        {
            ReferenceInfo<User> id = user.getReference();
            String password = entityStore.getPassword(id);
            cache.putPassword(id, password);
        }
    }

    private RaplaDefaultXMLContext createInputContext(EntityStore store, IdCreator idCreator, Category superCategory) throws RaplaException
    {
        RaplaDefaultXMLContext inputContext = new IOContext().createInputContext(logger, raplaLocale, i18n, store, idCreator, superCategory);
        RaplaNonValidatedInput xmlAdapter = new ConfigTools.RaplaReaderImpl();
        inputContext.put(RaplaNonValidatedInput.class, xmlAdapter);
        inputContext.put(Date.class, new Date(getLastRefreshed().getTime() - HISTORY_DURATION));
        inputContext.put(EntityHistory.class, history);
        final RaplaDefaultXMLContext inputContext1 = inputContext;
        return inputContext1;
    }

    private RaplaDefaultXMLContext createOutputContext(LocalCache cache) throws RaplaException
    {
        RaplaDefaultXMLContext outputContext = new IOContext().createOutputContext(logger, raplaLocale, i18n, cache.getSuperCategoryProvider(), true);
        outputContext.put(LocalCache.class, cache);
        return outputContext;

    }

    @Override public Collection<ImportExportEntity> getImportExportEntities(String systemId, int importExportDirection) throws RaplaException
    {
        try (Connection con = createConnection())
        {
            final RaplaDefaultXMLContext context = createOutputContext(cache);
            final RaplaSQL raplaSQL = new RaplaSQL(context);
            final Collection<ImportExportEntity> importExportEntities = raplaSQL.getImportExportEntities(systemId, importExportDirection, con);
            return importExportEntities;
        }
        catch (SQLException e)
        {
            throw new RaplaException("Error connecting to database");
        }
    }

    @Override public Date requestLock(String id, Long validMilliseconds) throws RaplaException
    {
        // no commit needed as getLocks will do a commit
        try (Connection con = createConnection())
        {
            final RaplaDefaultXMLContext context = createOutputContext(cache);
            final RaplaSQL raplaSQL = new RaplaSQL(context);
            final Date databaseTimestamp = raplaSQL.getDatabaseTimestamp(con);
            raplaSQL.requestLocks(con, databaseTimestamp, Collections.singletonList(id), validMilliseconds, false);
            final Date lastRequested = raplaSQL.getLastRequested(con, id);
            return lastRequested;
        }
        catch (SQLException e)
        {
            throw new RaplaException("Error connecting to database");
        }
    }

    @Override public void releaseLock(String id, Date updatedUntil) throws RaplaException
    {
        try (Connection con = createConnection())
        {
            final RaplaDefaultXMLContext context = createOutputContext(cache);
            final RaplaSQL raplaSQL = new RaplaSQL(context);
            raplaSQL.removeLocks(con, Collections.singletonList(id), updatedUntil, false);
            con.commit();
        }
        catch (SQLException e)
        {
            throw new RaplaException("Error connecting to database");
        }
    }

    //implement backup at disconnect
    //    final public void backupData() throws RaplaException {
    //        try {
    //
    //            if (backupFileName.length()==0)
    //            	return;
    //
    //            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    //            writeData(buffer);
    //            byte[] data = buffer.toByteArray();
    //            buffer.close();
    //            OutputStream out = new FileOutputStream(backupFileName);
    //            out.write(data);
    //            out.close();
    //            getLogger().info("Backup data to: " + backupFileName);
    //        } catch (IOException e) {
    //            getLogger().error("Backup error: " + e.getMessage());
    //            throw new RaplaException(e.getMessage());
    //        }
    //    }
    //
    //
    //    private void writeData( OutputStream out ) throws IOException, RaplaException
    //    {
    //    	RaplaXMLContext outputContext = new IOContext().createOutputContext( raplaLocale,i18n,cache.getSuperCategoryProvider(), true );
    //        RaplaMainWriter writer = new RaplaMainWriter( outputContext, cache );
    //        writer.setEncoding(backupEncoding);
    //        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out,backupEncoding));
    //        writer.setWriter(w);
    //        writer.printContent();
    //    }

}
