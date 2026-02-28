package com.app.master.service.core.service.tenant;

import com.app.master.service.core.dto.constants.Constant;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class TenantDataSource extends AbstractRoutingDataSource {

    @Override
    protected String determineCurrentLookupKey() {
        return Constant.DEFAULT;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = getResolvedDefaultDataSource().getConnection();
        connection.setSchema(TenantContext.getCurrentTenant());
        return connection;
    }

}
