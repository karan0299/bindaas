package edu.emory.cci.bindaas.core.rest.security;

import java.security.Principal;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.interceptor.security.DefaultSecurityContext;
import org.apache.cxf.jaxrs.ext.RequestHandler;
import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.message.Message;
import org.apache.cxf.security.SecurityContext;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import edu.emory.cci.bindaas.core.bundle.Activator;
import edu.emory.cci.bindaas.security.api.AuthenticationException;
import edu.emory.cci.bindaas.security.api.IAuthenticationProvider;
import edu.emory.cci.bindaas.security.api.IAuthorizationProvider;


public class SecurityHandler implements RequestHandler {
	private Log log = LogFactory.getLog(getClass());
	private boolean enableAuthorization ;
	private boolean enableAuthentication ;
	private Properties authenticationProps;
	private Properties authorizationProps;
	private String authenticationProviderClass;
	private String authorizationProviderClass;
	
	
	public void init() throws Exception
	{
		// do init here
	}
	

	@Override
 	public Response handleRequest(Message message, ClassResourceInfo arg1) {

		Principal authenticatedUser =  null;
		if(enableAuthentication)
		{
			IAuthenticationProvider authenticationProvider = locateAuthenticationProvider();
			if(authenticationProvider == null)
			{
				// authenticationProvider not found
				log.error("Unable to locate IAuthenticationProvider");
				return Response.serverError().entity("Error communicating with Authentication Module").build();
			}
			
			String[] usernamePassword = extractUsernamePassword(message);
			
			if(usernamePassword != null && authenticationProvider.isAuthenticationByUsernamePasswordSupported())
			{
				try {
					authenticatedUser = authenticationProvider.login(usernamePassword[0], usernamePassword[1], authenticationProps);
				} catch (AuthenticationException e) {
						log.error(e); // authentication failed
						return Response.status(401).header("WWW-Authenticate", "Basic")
								.build();
				}
				catch(Exception e)
				{
					log.error(e);
					return Response.serverError().entity("Error in Authentication Module").build();
				}
			}
			else
			{
				String securityToken = extractSecurityToken(message);
				if(securityToken!=null && authenticationProvider.isAuthenticationBySecurityTokenSupported())
				{
					try {
						authenticatedUser = authenticationProvider.login(securityToken, authenticationProps);
					} catch (AuthenticationException e) {
							log.error(e); // authentication failed
							return Response.status(401).header("WWW-Authenticate", "Basic")
									.build();
					}
					catch(Exception e)
					{
						log.error(e);
						return Response.serverError().entity("Error in Authentication Module").build();
					}
				}
				else if(usernamePassword == null && securityToken == null)
				{
					return Response.status(401).header("WWW-Authenticate", "Basic")
							.build();
				}
				else{
					return Response.serverError().entity("Authentication Module not configured correctly").build();
				}
			}
			
			// at this stage user is authenticated and principal is set
			
			if(enableAuthorization)
			{
				IAuthorizationProvider authorizationProvider = locateAuthorizationProvider();
				if(authorizationProvider == null)
				{
					// authorizationProvider not found
					log.error("Unable to locate IAuthorizationProvider");
					return Response.serverError().entity("Error communicating with Authorization Module").build();
				}
				try{
						boolean grantAccess = performAuthorization( message , authenticatedUser , authorizationProvider);
						if(!grantAccess)
						{
							return Response.status(
									Response.Status.UNAUTHORIZED.getStatusCode())
									.build();
						}
					}
				catch(Exception e)
				{
					log.error(e);
					return Response.serverError().entity("Error in Authorization Module").build();
				}
				
			}
		}		

		setSecurityContext(message , authenticatedUser);
		
		return null;
	}
	
	public boolean isEnableAuthorization() {
		return enableAuthorization;
	}


	public void setEnableAuthorization(boolean enableAuthorization) {
		this.enableAuthorization = enableAuthorization;
	}


	public boolean isEnableAuthentication() {
		return enableAuthentication;
	}


	public void setEnableAuthentication(boolean enableAuthentication) {
		this.enableAuthentication = enableAuthentication;
	}


	public Properties getAuthenticationProps() {
		return authenticationProps;
	}


	public void setAuthenticationProps(Properties authenticationProps) {
		this.authenticationProps = authenticationProps;
	}


	public Properties getAuthorizationProps() {
		return authorizationProps;
	}


	public void setAuthorizationProps(Properties authorizationProps) {
		this.authorizationProps = authorizationProps;
	}


	


	private void setSecurityContext(Message message, Principal authenticatedUser) {
		if(authenticatedUser!=null)
		{
			message.put(SecurityContext.class,
					createSecurityContext(authenticatedUser.getName()));
		}
		else
		{
			message.put(SecurityContext.class,
					createSecurityContext("anonymous"));
		}
		
	}

	public SecurityContext createSecurityContext(final String username) {
		Principal principal =  new Principal() {
			
			public String getName() {
				
				return username;
			}
			
			public String toString()
			{
				return username;
			}
			
			public boolean equals(Object o)
			{
				if(o instanceof Principal)
				{
					if(((Principal)o).getName().equals(username)) return true;
				}
				return false;
			}
		};;
		Subject subject = new Subject();
		subject.getPrincipals().add(principal);
		return new DefaultSecurityContext(subject);
	}
	
	
	private boolean performAuthorization(Message message,
			Principal authenticatedUser,
			IAuthorizationProvider authorizationProvider) throws Exception {
		HttpServletRequest request = (HttpServletRequest) message
				.get(AbstractHTTPDestination.HTTP_REQUEST);
		
		String pathInfo = (String) message.get("org.apache.cxf.request.url");
		
		
		Map<String,String> userAttributes = new HashMap<String,String>();
		userAttributes.put(IAuthorizationProvider.IP_ADDRESS, request.getRemoteAddr());
		userAttributes.put(IAuthorizationProvider.TIME_OF_AUTHENTICATION, GregorianCalendar.getInstance().getTime().toString());
		
		String actionId = request.getMethod();
		
		boolean result = authorizationProvider.isAuthorized(userAttributes, authenticatedUser.getName(), pathInfo, actionId, authorizationProps);
		
		
		return result;
	}


	private String extractSecurityToken(Message message) {
		Map protocolHeaders = (Map) message.get(Message.PROTOCOL_HEADERS);
		if (protocolHeaders.get("token") != null) {
			List listOfValues = (List) protocolHeaders.get("token");
			String token = (String) listOfValues.get(0);
			return token;
		}
		else
			return null;
	}


	private String[] extractUsernamePassword(Message message) {
		AuthorizationPolicy policy = (AuthorizationPolicy) message
				.get(AuthorizationPolicy.class);
		if (policy != null) {
			String username = policy.getUserName();
			String password = policy.getPassword();
			return new String[]{username,password};
		}
		else
		return null;
	}


	IAuthenticationProvider locateAuthenticationProvider()
	{
		
		final BundleContext context = Activator.getContext();
		ServiceReference[] serviceReferences;
		try {
			serviceReferences = context.getAllServiceReferences(IAuthenticationProvider.class.getName(), "(class=" +  authenticationProviderClass +")");
			if(serviceReferences.length > 0)
			{
				Object service = context.getService(serviceReferences[0]);
				if(service!=null)
				{
					return (IAuthenticationProvider) service; 
				}
			}
		} catch (InvalidSyntaxException e) {
			log.error(e);
			
		}
		
		return null;
		
	}
	
	IAuthorizationProvider locateAuthorizationProvider()
	{
		final BundleContext context = Activator.getContext();
		ServiceReference[] serviceReferences;
		try {
			serviceReferences = context.getAllServiceReferences(IAuthorizationProvider.class.getName(), "(class=" +  authorizationProviderClass +")");
			if(serviceReferences.length > 0)
			{
				Object service = context.getService(serviceReferences[0]);
				if(service!=null)
				{
					return (IAuthorizationProvider) service; 
				}
			}
		} catch (InvalidSyntaxException e) {
			log.error(e);
			
		}
		
		return null;
		
	}


	public String getAuthenticationProviderClass() {
		return authenticationProviderClass;
	}


	public void setAuthenticationProviderClass(String authenticationProviderClass) {
		this.authenticationProviderClass = authenticationProviderClass;
	}


	public String getAuthorizationProviderClass() {
		return authorizationProviderClass;
	}


	public void setAuthorizationProviderClass(String authorizationProviderClass) {
		this.authorizationProviderClass = authorizationProviderClass;
	}

}
