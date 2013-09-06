package edu.emory.cci.bindaas.core.apikey.impl;

import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;

import edu.emory.cci.bindaas.core.apikey.api.APIKey;
import edu.emory.cci.bindaas.core.apikey.api.APIKeyManagerException;
import edu.emory.cci.bindaas.core.apikey.api.IAPIKeyManager;
import edu.emory.cci.bindaas.core.model.hibernate.HistoryLog;
import edu.emory.cci.bindaas.core.model.hibernate.HistoryLog.ActivityType;
import edu.emory.cci.bindaas.core.model.hibernate.UserRequest;
import edu.emory.cci.bindaas.core.model.hibernate.UserRequest.Stage;
import edu.emory.cci.bindaas.security.api.BindaasUser;

public class DefaultAPIKeyManager implements IAPIKeyManager {

	private Log log = LogFactory.getLog(getClass());
	private SessionFactory sessionFactory;
	
	public SessionFactory getSessionFactory() {
		return sessionFactory;
	}

	public void setSessionFactory(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	private APIKey userRequestToAPIKey(UserRequest userRequest)
	{
		APIKey apiKey = new APIKey();
		apiKey.setValue(userRequest.getApiKey());
		apiKey.setExpires(userRequest.getDateExpires());
		apiKey.setEmailAddress(userRequest.getEmailAddress());
		apiKey.setFirstName(userRequest.getFirstName());
		apiKey.setLastName(userRequest.getLastName());
		return apiKey;
	}

	
	
	
	@Override
	public APIKey generateAPIKey(BindaasUser bindaasUser , Date dateExpires, String initiatedBy ,String comments , ActivityType activityType , boolean throwErrorIfAlreadyExists)
			throws APIKeyManagerException { 
		Session session = sessionFactory.openSession();
		try {
			session.beginTransaction();

			String emailAddress = bindaasUser
					.getProperty(BindaasUser.EMAIL_ADDRESS) != null ? bindaasUser
					.getProperty(BindaasUser.EMAIL_ADDRESS).toString()
					: bindaasUser.getName() + "@" + bindaasUser.getDomain();
			String firstName = bindaasUser
					.getProperty(BindaasUser.FIRST_NAME) != null ? bindaasUser
					.getProperty(BindaasUser.FIRST_NAME).toString()
					: bindaasUser.getName();
			String lastName = bindaasUser.getProperty(BindaasUser.LAST_NAME) != null ? bindaasUser
					.getProperty(BindaasUser.LAST_NAME).toString()
					: bindaasUser.getName();

			@SuppressWarnings("unchecked")
			List<UserRequest> listOfValidKeys = (List<UserRequest>) session
					.createCriteria(UserRequest.class)
					.add(Restrictions.eq("stage", Stage.accepted.name()))
					.add(Restrictions.eq("emailAddress", emailAddress))
					.list();

			if (listOfValidKeys != null && listOfValidKeys.size() > 0) {
				
				
				UserRequest request = listOfValidKeys.get(0);
				if (request.getDateExpires().after(new Date())) {
					if(throwErrorIfAlreadyExists)
					{
						throw new APIKeyManagerException("APIKey for the user already exists");
					}
					else
					{
						return userRequestToAPIKey(request);
					}
					
				} else {
					log.warn("The API Key of user [" + bindaasUser
							+ "] has expired");
					request.setStage("expired");
					session.save(request);
				}
			} 
				
			// generate api key for first time user
				UserRequest userRequest = new UserRequest();
				userRequest.setStage(Stage.accepted);
				userRequest.setApiKey(URLEncoder.encode(UUID.randomUUID()
						.toString() , "UTF-8"));
				userRequest.setDateExpires(dateExpires);

				userRequest.setEmailAddress(emailAddress);
				userRequest.setFirstName(firstName);
				userRequest.setLastName(lastName);

				session.save(userRequest);
				HistoryLog historyLog = new HistoryLog();
				historyLog.setActivityType(activityType.toString());
				historyLog
						.setComments(comments);
				historyLog.setInitiatedBy(initiatedBy);
				historyLog.setUserRequest(userRequest);

				session.save(historyLog);
				session.getTransaction().commit();
				return userRequestToAPIKey(userRequest);

		} catch (Exception e) {
			log.error(e);
			session.getTransaction().rollback();
			throw new APIKeyManagerException(e);
		} finally {
			session.close();
		}
}

	@Override
	public APIKey createShortLivedAPIKey(BindaasUser bindaasUser, int lifetime , String applicationId)
			throws APIKeyManagerException {
		Session session = sessionFactory.openSession();
		try {
			session.beginTransaction();

			String emailAddress = bindaasUser
					.getProperty(BindaasUser.EMAIL_ADDRESS) != null ? bindaasUser
					.getProperty(BindaasUser.EMAIL_ADDRESS).toString()
					: bindaasUser.getName() + "@" + bindaasUser.getDomain();
			

			@SuppressWarnings("unchecked")
			List<UserRequest> listOfValidKeys = (List<UserRequest>) session
					.createCriteria(UserRequest.class)
					.add(Restrictions.eq("stage", Stage.accepted.name()))
					.add(Restrictions.eq("emailAddress", emailAddress))
					.list();

			if (listOfValidKeys != null && listOfValidKeys.size() > 0) {
				UserRequest request = listOfValidKeys.get(0); 
				if (request.getDateExpires().after(new Date())) {

					// generate a short lived api-key for this user
					UserRequest sessionKey = new UserRequest();
					sessionKey.setApiKey(UUID.randomUUID().toString());
					sessionKey.setFirstName(request.getFirstName());
					sessionKey.setLastName(request.getLastName());
					sessionKey.setEmailAddress(emailAddress);
					sessionKey.setReason(request.getReason());
					sessionKey.setRequestDate(new Date());
					sessionKey.setStage(Stage.accepted);
					
					GregorianCalendar calendar = new GregorianCalendar();
					
					calendar.add(Calendar.SECOND , lifetime);
					Date newExpirationDate = request.getDateExpires().before(calendar.getTime()) ? request.getDateExpires() : calendar.getTime();
					sessionKey.setDateExpires(newExpirationDate);
					
					
					session.save(sessionKey);

					HistoryLog historyLog = new HistoryLog();
					historyLog.setActivityType(ActivityType.SYSTEM_APPROVE);
					historyLog.setComments("Short-Lived APIKey");
					historyLog.setInitiatedBy(applicationId);
					historyLog.setUserRequest(sessionKey);

					session.save(historyLog);
					session.getTransaction().commit();
					
					return userRequestToAPIKey(sessionKey);
				} else {
					
					throw new APIKeyManagerException("The API Key of user [" + bindaasUser
							+ "] has expired. The User must reapply");
				}
			} else {
				throw new APIKeyManagerException("Cannot generate API-Key for [" + bindaasUser + "]. The User must apply for it first!!");
			}

		} catch (Exception e) {
			log.error(e);
			session.getTransaction().rollback();
			throw new APIKeyManagerException(e);
		} finally {
			session.close();
		}

	}

	@Override
	public BindaasUser lookupUser(String apiKey) throws APIKeyManagerException {
		Session session = sessionFactory.openSession();
		try{
			
			@SuppressWarnings("unchecked")
			List<UserRequest> listOfValidKeys = (List<UserRequest>) session.createCriteria(UserRequest.class).add(Restrictions.eq("stage",	Stage.accepted.name())).add(Restrictions.eq("apiKey", apiKey)).list();
			if(listOfValidKeys!=null && listOfValidKeys.size() > 0)
			{
				UserRequest request = listOfValidKeys.get(0);
				if(request.getDateExpires().after(new Date()))
				{
					BindaasUser bindaasUser = new BindaasUser(request.getEmailAddress());
					bindaasUser.addProperty(BindaasUser.EMAIL_ADDRESS, request.getEmailAddress());
					bindaasUser.addProperty(BindaasUser.FIRST_NAME, request.getFirstName());
					bindaasUser.addProperty(BindaasUser.LAST_NAME, request.getLastName());
					return bindaasUser;
				}
			}
		}
		catch(Exception e)
		{
			log.error(e);
			throw new APIKeyManagerException(e);
		}
		
		return null;
	}

	@Override
	public Integer revokeAPIKey(BindaasUser bindaasUser,String initiatedBy ,String comments , ActivityType activityType)
			throws APIKeyManagerException {
		Session session = sessionFactory.openSession();
		try{
			String emailAddress = bindaasUser
					.getProperty(BindaasUser.EMAIL_ADDRESS) != null ? bindaasUser
					.getProperty(BindaasUser.EMAIL_ADDRESS).toString()
					: bindaasUser.getName() + "@" + bindaasUser.getDomain();
			
			@SuppressWarnings("unchecked")
			List<UserRequest> listOfValidKeys = (List<UserRequest>) session.createCriteria(UserRequest.class).add(Restrictions.eq("stage",	Stage.accepted.name())).add(Restrictions.eq("emailAddress", emailAddress)).list();
			if(listOfValidKeys!=null && listOfValidKeys.size() > 0)
			{
				session.beginTransaction();
				for(UserRequest usrRequest : listOfValidKeys)
				{
					usrRequest.setStage(Stage.revoked);
				}
				session.getTransaction().commit();
				return listOfValidKeys.size();
			}
			else
				return 0;
			
			
		}catch(Exception e)
		{
			log.error(e);
			session.getTransaction().rollback();
			throw new APIKeyManagerException(e);
		}
		finally{
			if(session!=null)
				session.close();
		}
		
	}

	@Override
	public Integer revokeAPIKey(String apiKey,String initiatedBy ,String comments , ActivityType activityType) throws APIKeyManagerException {
		Session session = sessionFactory.openSession();
		try{
			
			@SuppressWarnings("unchecked")
			List<UserRequest> listOfValidKeys = (List<UserRequest>) session.createCriteria(UserRequest.class).add(Restrictions.eq("stage",	Stage.accepted.name())).add(Restrictions.eq("apiKey", apiKey)).list();
			if(listOfValidKeys!=null && listOfValidKeys.size() > 0)
			{
				session.beginTransaction();
				for(UserRequest usrRequest : listOfValidKeys)
				{
					usrRequest.setStage(Stage.revoked);
				}
				session.getTransaction().commit();
				return listOfValidKeys.size();
			}
			else
				return 0;
			
			
		}catch(Exception e)
		{
			log.error(e);
			session.getTransaction().rollback();
			throw new APIKeyManagerException(e);
		}
		finally{
			if(session!=null)
				session.close();
		}
	}

	@Override
	public APIKey createAPIKeyRequest(BindaasUser bindaasUser,
			Date dateExpires, String initiatedBy, String comments,
			ActivityType activityType) throws APIKeyManagerException {
		throw new APIKeyManagerException("Method Not Implemented"); // TODO : implement this later
	}

	@Override
	public APIKey modifyAPIKey(Long id, Stage stage, Date dateExpires,
			String initiatedBy, String comments, ActivityType activityType)
			throws APIKeyManagerException {
		Session session = sessionFactory.openSession();

		try {
			session.beginTransaction();
			@SuppressWarnings("unchecked")
			List<UserRequest> list = session.createCriteria(UserRequest.class)
					.add(Restrictions.eq("id", id)).list();
			if (list != null && list.size() > 0) {
				UserRequest userRequest = list.get(0);
				HistoryLog historyLog = new HistoryLog();
				historyLog.setComments(comments);
				historyLog.setInitiatedBy(initiatedBy);
				historyLog.setUserRequest(userRequest);
				historyLog.setActivityType(activityType);

				userRequest.setStage(stage);

				switch (activityType) {
				case APPROVE:
				case REFRESH:
					userRequest.setApiKey(URLEncoder.encode(UUID.randomUUID()
							.toString(), "UTF-8"));
					userRequest.setDateExpires(dateExpires);

				case REVOKE:
				case DENY:
				default:
					userRequest.setStage(stage);

				}

				session.save(userRequest);
				session.save(historyLog);
				session.getTransaction().commit();
				return userRequestToAPIKey(userRequest);
			} else {
				throw new Exception("No results found matching id = [" + id
						+ "]");
			}

		} catch (Exception e) {
			session.getTransaction().rollback();
			log.error(e);
			throw new APIKeyManagerException(e);
		} finally {
			session.close();
		}

	}

}