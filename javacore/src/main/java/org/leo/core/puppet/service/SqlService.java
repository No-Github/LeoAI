package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.JavaDatabaseConnectionAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlService extends ComponentService {

    private final JavaDatabaseConnectionAdapter connectionAdapter = new JavaDatabaseConnectionAdapter();

    public SqlService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>(connectionAdapter.adapt(connection));
        payload.put("sql", sqlScript);
        return invokeComponent("DatabaseComponent", payload);
    }
}
