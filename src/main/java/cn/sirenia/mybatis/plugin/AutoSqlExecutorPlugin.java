package cn.sirenia.mybatis.plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.bind.PropertyException;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import cn.sirenia.mybatis.sql.provider.TwSqlProvider;
import cn.sirenia.mybatis.util.ReflectHelper;
import cn.sirenia.mybatis.util.ScriptSqlGenerator;
import cn.sirenia.mybatis.util.XMLMapperConf;

@Intercepts({
		@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
				RowBounds.class, ResultHandler.class }),
		@Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }) })
//AutoSqlExecutorPlugin2的另一种实现方式，这个类改的是SqlSource
public class AutoSqlExecutorPlugin implements Interceptor {

	// private static final Logger logger =
	// Logger.getLogger(ExecutorPlugin.class);
	private String dialect = ""; // 数据库方言
	private final Map<String, Method> providerMethodMap = new HashMap<>();
	{
		Method[] methods = ScriptSqlGenerator.class.getMethods();
		for (Method m : methods) {
			providerMethodMap.put(m.getName(), m);
		}
	}
	@Override
	public Object intercept(Invocation invocation) throws Throwable {
		Object[] args = invocation.getArgs();
		MappedStatement statement = (MappedStatement) args[0];
		Configuration configuration = statement.getConfiguration();
		Object parameterObject = args[1];
		// statement.getSqlSource().getBoundSql(parameterObject);
		String sql = statement.getBoundSql(parameterObject).getSql();
		String statementId = statement.getId();
		int index = statementId.lastIndexOf(".");
		String methodName = statementId.substring(index + 1);
		if (("sirenia-" + methodName).equals(sql)) {// 只有第一次会执行，因为执行一次后sql语句已经被改变。
			synchronized (this) {
				if (("sirenia-" + methodName).equals(sql)) {
					String mapperClazzName = statementId.substring(0, index);
					ScriptSqlGenerator provider = new ScriptSqlGenerator();
					Method method = providerMethodMap.get(methodName);
					String script = (String) method.invoke(provider, XMLMapperConf.of(configuration, mapperClazzName,dialect));
					// 不支持写<selectKey>，不支持<include>
					// "<script>select * from sys_user <where> 1=1</where>order
					// by #{orderByClause}</script>";
					Class<?> paramClazz = parameterObject == null ? null : parameterObject.getClass();
					SqlSource dynamicSqlSource = new XMLLanguageDriver().createSqlSource(configuration, script,
							paramClazz);
					// String msg = "sql for %s is %s,replaced by\r\n%s";
					// logger.debug(String.format(msg, statementId,sql,script));
					ReflectHelper.setValueByFieldName(statement, "sqlSource", dynamicSqlSource);
				}
			}
		}
		Object ret = invocation.proceed();
		return ret;
	}
	@Override
	public Object plugin(Object target) {
		return Plugin.wrap(target, this);
	}
	@Override
	public void setProperties(Properties p) {
		dialect = p.getProperty("dialect");
		if (dialect == null || dialect.trim().isEmpty()) {
			try {
				throw new PropertyException("dialect property is not found!");
			} catch (PropertyException e) {
				e.printStackTrace();
			}
		}
	}
}
