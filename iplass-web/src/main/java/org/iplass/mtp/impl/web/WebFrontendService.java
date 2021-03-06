/*
 * Copyright (C) 2011 INFORMATION SERVICES INTERNATIONAL - DENTSU, LTD. All Rights Reserved.
 *
 * Unless you have purchased a commercial license,
 * the following license terms apply:
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.iplass.mtp.impl.web;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.iplass.mtp.impl.web.RequestPath.PathType;
import org.iplass.mtp.impl.web.fileupload.FileScanner;
import org.iplass.mtp.spi.Config;
import org.iplass.mtp.spi.Service;
import org.iplass.mtp.spi.ServiceConfigrationException;
import org.iplass.mtp.web.actionmapping.definition.ClientCacheType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WebFrontendService implements Service {

	private static Logger logger = LoggerFactory.getLogger(WebFrontendService.class);

	private String tempFileDir;
	private String staticContentPath;
	private String defaultContentType;
	private ClientCacheType defaultClientCacheType;

	
	/**
	 * iPLAss管理対象外のパスの定義のPattern。
	 * ServletContextPathより後のパスのパターンを指定する。
	 * このパスに一致するパスの場合は、DispatcherFilterで処理せず、
	 * 後続FilterChainにdoFilterする。
	 * 静的コンテンツのパス、独自実装のServletのパスなどを指定する想定。
	 */
	private Pattern excludePathes;
	
	/**
	 * DispatcherFilterにてテナントの確定処理まで実施するが、iPLAss内に定義されるAction/WebAPIを呼び出さず、
	 * 後続FilterChainにdoFilterするパスのPattern。
	 * テナントコンテキストパスより後のパスのパターンを指定。
	 */
	private Pattern throughPathes;
	
	/**
	 * iPLAss内に定義されるWebAPI(REST)の呼び出しと認識するパス定義。
	 * テナントコンテキストパスより後のパスを指定。
	 * ※Patternではなく、定義されたパスに前方一致するパスをWebAPIコールと判断する。
	 * 
	 */
	private List<String> restPath;

	/**
	 * リクエストを受付するパスのPattern。
	 * テナントコンテキストパスより後のパスのパターンを指定。
	 * サーバ毎に実行するAction/WebAPIを限定したい場合等に利用。
	 */
	private Pattern acceptPathes;
	
	/**
	 * リクエストを拒否するパスのPattern。
	 * テナントコンテキストパスより後のパスのパターンを指定。
	 * サーバ毎に実行するAction/WebAPIを限定したい場合等に利用。
	 * acceptPathesと、rejectPathes両方指定された場合、
	 * 適用順は、acceptPathes -> rejectPathesとなる。
	 * 
	 * acceptPathesでマッチしても、rejectPathesでもマッチしたら、拒否となる。
	 * 
	 */
	private Pattern rejectPathes;
	
	private List<RequestRestriction> requestRestrictions;
	private RequestRestriction defaultRequestRestriction;
	
	/** ログアウト時にキックするURL */
	private String logoutUrl;

	/** エラー画面セレクター */
	private ErrorUrlSelector errorUrlSelector;
	/** Login画面セレクタ */
	private LoginUrlSelector loginUrlSelector;

	/** ContentDisposition設定 */
	private List<ContentDispositionPolicy> contentDispositionPolicies;

	/** ウィルススキャン実行 */
	private FileScanner uploadFileScanner;

	/** ExecMagicByteCheck実施 */
	private boolean isExecMagicByteCheck;

	/** ダイレクトアクセス時のポート */
	private String directAccessPort;

	private int transactionTokenMaxSize = 50;

	private List<String> welcomeAction;

	private boolean redirectAfterLogin = true;

	private boolean tenantAsDomain;
	private String fixedTenant;
	
	public boolean isAcceptPathes(String path) {
		boolean ret = (getAcceptPathes() == null || getAcceptPathes().matcher(path).matches());
		if (getRejectPathes() != null) {
			ret = ret && !getRejectPathes().matcher(path).matches();
		}
		return ret;
	}

	public boolean isExcludePath(String path) {
		return getExcluePathes() != null && getExcluePathes().matcher(path).matches();
	}

	public boolean isThroughPath(String path) {
		return getThroughPathes() != null && getThroughPathes().matcher(path).matches();
	}

	public boolean isRestPath(String path) {
		if (restPath != null) {
			for (String rp: restPath) {
				if (path.startsWith(rp)) {
					return true;
				}
			}
		}

		return false;
	}

	public Pattern getAcceptPathes() {
		return acceptPathes;
	}

	public Pattern getRejectPathes() {
		return rejectPathes;
	}
	
	public Pattern getThroughPathes() {
		return throughPathes;
	}
	public String getFixedTenant() {
		return fixedTenant;
	}

	public boolean isTenantAsDomain() {
		return tenantAsDomain;
	}

	public boolean isRedirectAfterLogin() {
		return redirectAfterLogin;
	}

	public List<String> getWelcomeAction() {
		return welcomeAction;
	}

	public int getTransactionTokenMaxSize() {
		return transactionTokenMaxSize;
	}

	public boolean isExecMagicByteCheck() {
		return isExecMagicByteCheck;
	}

	public FileScanner getUploadFileScanner() {
		return uploadFileScanner;
	}

	public LoginUrlSelector getLoginUrlSelector() {
		return loginUrlSelector;
	}

	public ErrorUrlSelector getErrorUrlSelector() {
		return errorUrlSelector;
	}

	public String getTempFileDir() {
		return tempFileDir;
	}

	public String getStaticContentPath() {
		return staticContentPath;
	}

	@Override
	public void destroy() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public void init(Config config) {
		tempFileDir = config.getValue("tempFileDir");

		if (tempFileDir != null) {
			File tFile = new File(this.tempFileDir);
			if (!tFile.exists()) {
				logger.debug("tempFileDir not exist:" + tFile.getAbsolutePath());

				try {
					if (tFile.mkdirs()) {
						logger.info("create tempFileDir:" + tFile.getAbsolutePath());
						//権限設定（777）
						tFile.setReadable(true, false);
						tFile.setWritable(true, false);
						tFile.setExecutable(true, false);
					} else {
						throw new ServiceConfigrationException("tempFileDir create failed:" + tFile.getAbsolutePath());
					}
				} catch(SecurityException e) {
					throw new ServiceConfigrationException("tempFileDir create failed:" + tFile.getAbsolutePath());
				}
			} else {
				logger.debug("tempFileDir is existed:" + tFile.getAbsolutePath());
			}
		} else {
			logger.debug("tempFileDir is undefined");
		}

		staticContentPath = config.getValue("staticContentPath");

		String defaultContentType = config.getValue("defaultContentType");
		if (defaultContentType == null) {
			defaultContentType = "text/html; charset=utf-8";
		}
		this.defaultContentType = defaultContentType;

		String defaultClientCacheType = config.getValue("defaultClientCacheType");
		if (defaultClientCacheType != null) {
			if (defaultClientCacheType.equals("NO_CACHE")) {
				this.defaultClientCacheType = ClientCacheType.NO_CACHE;
			} else if (defaultClientCacheType.equals("CACHE")) {
				this.defaultClientCacheType = ClientCacheType.CACHE;
			} else {
				throw new ServiceConfigrationException("defaultClientCacheType incorrect:" + defaultClientCacheType);
			}
		}

		errorUrlSelector = (ErrorUrlSelector) config.getBean("errorUrlSelector");

		loginUrlSelector = (LoginUrlSelector) config.getBean("loginUrlSelector");

		
		List<String> accept = config.getValues("acceptPath");
		if (accept != null && accept.size() > 0) {
			acceptPathes = Pattern.compile(String.join("|", accept));
		}
		
		List<String> reject = config.getValues("rejectPath");
		if (reject != null && reject.size() > 0) {
			rejectPathes = Pattern.compile(String.join("|", reject));
		}
		
		List<String> exclude = config.getValues("excludePath");
		if (exclude != null && exclude.size() > 0) {
			excludePathes = Pattern.compile(String.join("|", exclude));
		}

		List<String> throughPathList = config.getValues("throughPath");
		if (throughPathList != null && throughPathList.size() > 0) {
			throughPathes = Pattern.compile(String.join("|", throughPathList));
		}

		requestRestrictions = config.getValues("requestRestriction", RequestRestriction.class);
		if (requestRestrictions != null) {
			for (RequestRestriction rr: requestRestrictions) {
				if (rr.getPathPattern() == null) {
					defaultRequestRestriction = rr;
				}
			}
		}
		if (defaultRequestRestriction == null) {
			defaultRequestRestriction = createDefaultRequestRestriction(config);
		}
		
		restPath = config.getValues("restPath");

		logoutUrl = config.getValue("logoutUrl");
		if(logoutUrl == null || logoutUrl.length() == 0) {
			throw new ServiceConfigrationException("logoutUrl is not set");
		}

		contentDispositionPolicies = (List<ContentDispositionPolicy>) config.getBeans("contentDispositionPolicy");

		uploadFileScanner = (FileScanner) config.getBean("uploadFileScanner");

		isExecMagicByteCheck = Boolean.valueOf(config.getValue("isExecMagicByteCheck"));

		directAccessPort = config.getValue("directAccessPort");

		if (config.getValue("transactionTokenMaxSize") != null) {
			transactionTokenMaxSize = Integer.parseInt(config.getValue("transactionTokenMaxSize"));
		}

		welcomeAction = config.getValues("welcomeAction");

		if (config.getValue("redirectAfterLogin") != null) {
			redirectAfterLogin = Boolean.valueOf(config.getValue("redirectAfterLogin"));
		}

		if (config.getValue("tenantAsDomain") != null) {
			tenantAsDomain = Boolean.valueOf(config.getValue("tenantAsDomain"));
		}

		fixedTenant = config.getValue("fixedTenant");
	}
	
	private RequestRestriction createDefaultRequestRestriction(Config config) {
		RequestRestriction rr  =new RequestRestriction();
		rr.setAllowMethods(Arrays.asList("*"));
		rr.setAllowContentTypes(Arrays.asList("*/*"));
		rr.setMaxFileSize(config.getValue("maxUploadFileSize", Long.class, -1L));
		
		rr.inited(this, config);
		return rr;
	}
	
	public RequestRestriction getRequestRestriction(String metaDataName, PathType type) {
		String path = null;
		
		if (requestRestrictions != null) {
			switch (type) {
			case ACTION:
				path = "/" + metaDataName;
				for (RequestRestriction rr: requestRestrictions) {
					if (rr.getPathPattern() != null && rr.getPathPatternCompile().matcher(path).matches()) {
						return rr;
					}
				}
				if (welcomeAction != null) {
					int slaIndex = path.lastIndexOf('/');
					String shortName = slaIndex < 0 ? path: path.substring(slaIndex + 1);
					
					for (String wa: welcomeAction) {
						if (wa.equals(shortName)) {
							String remainPath = path.substring(0, slaIndex + 1);
							for (RequestRestriction rr: requestRestrictions) {
								if (rr.getPathPattern() != null && rr.getPathPatternCompile().matcher(remainPath).matches()) {
									return rr;
								}
							}
						}
					}
				}
				break;
			case REST:
				for (String rp : restPath) {
					path = rp + metaDataName;
					for (RequestRestriction rr: requestRestrictions) {
						if (rr.getPathPattern() != null && rr.getPathPatternCompile().matcher(path).matches()) {
							return rr;
						}
					}
				}
				break;
			default:
				break;
			}
		}
		return defaultRequestRestriction;
	}

	public List<RequestRestriction> getRequestRestrictions() {
		return requestRestrictions;
	}

	public RequestRestriction getDefaultRequestRestriction() {
		return defaultRequestRestriction;
	}

	public String getDefaultContentType() {
		return defaultContentType;
	}

	public ClientCacheType getDefaultClientCacheType() {
		return defaultClientCacheType;
	}

	@Deprecated
	public long getMaxUploadFileSize() {
		return defaultRequestRestriction.getMaxFileSize();
	}

	/**
	 * テナント、Auth、Dispatchで除外するURLの定義を取得します。
	 * @return テナント、Auth、Dispatchで除外するURLの定義
	 */
	public Pattern getExcluePathes() {
	    return excludePathes;
	}

	/**
	 * ログアウト時にキックするURLを取得します。
	 * @return ログアウト時にキックするURL
	 */
	public String getLogoutUrl() {
	    return logoutUrl;
	}

	public List<String> getRestPath() {
		return restPath;
	}

	public List<ContentDispositionPolicy> getContentDispositionPolicy() {
		return contentDispositionPolicies;
	}

	public String getDirectAccessPort() {
		return directAccessPort;
	}

}
