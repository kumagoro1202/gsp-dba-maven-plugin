package jp.co.tis.gsp.test.util.dialect;

import org.apache.maven.plugin.MojoExecutionException;

import jp.co.tis.gsp.tools.dba.dialect.MysqlDialect;
import jp.co.tis.gsp.tools.dba.dialect.param.ExportParams;
import jp.co.tis.gsp.tools.dba.dialect.param.ImportParams;

public class MysqlGeneralTestDialect extends MysqlDialect {

    public void exportSchema(ExportParams params) throws MojoExecutionException {
        exportSchemaGeneral(params);
    }

    public void importSchema(ImportParams params) throws MojoExecutionException {
        importSchemaGeneral(params);
    }
}
