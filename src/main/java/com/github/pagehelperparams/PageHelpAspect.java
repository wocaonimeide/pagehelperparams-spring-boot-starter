package com.github.pagehelperparams;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;

import com.github.pagehelper.PageHelper;

/**
 * TODO 用于增强MyBatis使用PageHelper 插件进行分页操作
 * 不用再service代码中指定PageHelper.startPage(..,..) 根据参数名称自动推断
 * 并且解决PageHelper中ThreadLocal变量无法释放的bug
 * 
 * @author huangYu
 * @version 1.0
 * @since 1.8+
 * @createTime 2018年12月18日 下午2:34:46
 */
public class PageHelpAspect implements Serializable {

    private static final long serialVersionUID = -2725267829120542073L;
    private Logger logger = LoggerFactory.getLogger(getClass());

	private PageHelpProperties properties;

	private Map<Method, String> sMap = new HashMap<Method, String>();

	private Map<Method, ParamDesc[]> paramDescMap = new ConcurrentHashMap<Method, ParamDesc[]>();

	private List<Method> notHasPageInfoMethods=new CopyOnWriteArrayList<>();

	private Configuration configuration;

	public PageHelpAspect(Object object,PageHelpProperties properties,Configuration configuration) {
		this.properties = properties;
		this.configuration=configuration;
		Method [] methods=object.getClass().getDeclaredMethods();
		for (Method method : methods) {
			int paramCount=method.getParameterCount();
			if (paramCount == 0){
				notHasPageInfoMethods.add(method);
			}
		}
	}

	public void setPageInfo( Method method, Object[] args) {
		if (notHasPageInfoMethods.contains(method)) {
			return;
		}
		Integer pageNo = null;
		Integer pageSize = null;
		if (logger.isDebugEnabled()) {
			logger.debug("开始查找pageNo和pageSize的参数信息");
		}
		ParamDesc[] descs = paramDescMap.get(method);
		ParamDesc noDesc = null;
		ParamDesc sizeDesc = null;
		if (null == descs) {
			noDesc = pageInfo(method, properties.getPageNoParams());
			sizeDesc = pageInfo(method, properties.getPageSizeParams());
			if (null != noDesc && null != sizeDesc) {
				ParamDesc[] descs2 = new ParamDesc[2];
				descs2[0] = noDesc;
				descs2[1] = sizeDesc;
				paramDescMap.put(method, descs2);
			}else {
				notHasPageInfoMethods.add(method);
				return;
			}
		} else {
			noDesc = descs[0];
			sizeDesc = descs[1];
		}
		if (logger.isDebugEnabled()) {
			logger.debug("成功查找到了参数信息");
		}
		try {
			pageNo = pageValue(noDesc, args);
			pageSize = pageValue(sizeDesc, args);
			if (pageNo != null && null!=pageSize) {
				if (pageNo>=0 && pageSize>=0){
					PageHelper.startPage(pageNo, pageSize);
				}else {
					PageHelper.startPage(properties.getDefaultPageNo(),properties.getDefaultPageSize());
				}
			}
		} catch (Exception e) {
			// TODO: do noting
		}
	}

	/**
	 * 获取参数中的pageNo
	 * 
	 * @param method
	 * @param pageNames
	 * @return
	 */
	private ParamDesc pageInfo(Method method, List<String> pageNames) {
		ParamDesc desc = null;
		// 获取到当前方法的所有参数
		Parameter[] parameters = method.getParameters();
		if (null != parameters && parameters.length > 0) {
			Parameter parameter = null;
			String name = null;
			String[] paraNames = getParamNames(method);
			for (int i = 0; i < paraNames.length; i++) {
				name = paraNames[i];
				if (logger.isDebugEnabled()) {
					logger.debug("当前参数的名称:" + name);
				}
				for (String matchName : pageNames) {
					if (name.equalsIgnoreCase(matchName)) {
						desc = new ParamDesc();
						desc.setFieldOrParam(Boolean.FALSE);
						desc.setParaIndex(i);
						desc.setParaName(name);
						return desc;
					}
				}
			}
			// 如果程序走到此处，说明当前的参数的名称没有匹配的
			// 查看当前参数的内部属性是否有匹配的
			for (int i = 0; i < parameters.length; i++) {
				parameter = parameters[i];
				Class<?> class1 = parameter.getType();
				if (!BeanUtils.isSimpleProperty(class1)) {
					if (logger.isDebugEnabled()) {
						logger.debug("当前参数 {} 不是简单属性开始查找内部属性", name);
					}
					Field[] fields = class1.getDeclaredFields();
					for (Field field : fields) {
						String fieldName = field.getName();
						for (String matchName : pageNames) {
							if (fieldName.equalsIgnoreCase(matchName)) {
								desc = new ParamDesc();
								desc.setFieldOrParam(Boolean.TRUE);
								desc.setParaIndex(i);
								desc.setParaName(name);
								desc.setFieldName(fieldName);
								return desc;
							}
						}
					}
				}
			}
		}
		return desc;
	}

	/**
	 * 获取分页参数值
	 * @param desc
	 * @param args
	 * @return
	 * @throws Exception
	 */
	private Integer pageValue(ParamDesc desc, Object[] args) throws Exception {
		if (desc.getFieldOrParam()) {
			// 说明是字段内部
			Integer index = desc.getParaIndex();
			String fieldName = desc.getFieldName();
			Object object = args[index];
			MetaObject metaObject=configuration.newMetaObject(object);
			return Integer.valueOf(metaObject.getValue(fieldName).toString());
		} else {
			// 说明是参数上
			Integer index = desc.getParaIndex();
			Object object = args[index];
			return Integer.valueOf(object.toString());
		}
	}

	private String[] getParamNames(Method method) {
		StringBuilder builder = new StringBuilder("");
		Parameter[] parameters = method.getParameters();
		for (Parameter parameter : parameters) {
			builder.append(parameter.getParameterizedType().getTypeName());
		}
		String className = builder.toString();
		Method realMethod = null;
		for (Map.Entry<Method, String> entry : sMap.entrySet()) {
			String val = entry.getValue();
			if (val.equals(className)) {
				realMethod = entry.getKey();
			}
		}
		DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
		String[] paraNames = discoverer.getParameterNames(realMethod);
		return paraNames;
	}
}

class ParamDesc {
	/**
	 * 标志分页数据是在某个对象里面还是放到方法参数上面 true 为对象里面 false 为 参数上面
	 */
	private Boolean fieldOrParam;

	/**
	 * 方法 参数的index
	 */
	private Integer paraIndex;

	/**
	 * 方法参数的名称
	 */
	private String paraName;

	/**
	 * 字段名称
	 */
	private String fieldName;

	public Boolean getFieldOrParam() {
		return fieldOrParam;
	}

	public void setFieldOrParam(Boolean fieldOrParam) {
		this.fieldOrParam = fieldOrParam;
	}

	public Integer getParaIndex() {
		return paraIndex;
	}

	public void setParaIndex(Integer paraIndex) {
		this.paraIndex = paraIndex;
	}

	public String getParaName() {
		return paraName;
	}

	public void setParaName(String paraName) {
		this.paraName = paraName;
	}

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

}
