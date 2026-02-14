package jp.co.tis.gsp.test.util.dialect;

import org.apache.maven.plugin.MojoExecutionException;

import jp.co.tis.gsp.tools.dba.dialect.H2Dialect;
import jp.co.tis.gsp.tools.dba.dialect.param.ExportParams;
import jp.co.tis.gsp.tools.dba.dialect.param.ImportParams;

public class H2GeneralTestDialect extends H2Dialect {

    public void exportSchema(ExportParams params) throws MojoExecutionException {
        exportSchemaGeneral(params);
    }

    public void importSchema(ImportParams params) throws MojoExecutionException {
        importSchemaGeneral(params);
    }
}
