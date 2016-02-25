package com.pmease.gitplex.core.manager.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.eclipse.jgit.lib.PersonIdent;
import org.hibernate.Query;
import org.hibernate.ReplicationMode;
import org.hibernate.Session;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.pmease.commons.hibernate.Sessional;
import com.pmease.commons.hibernate.Transactional;
import com.pmease.commons.hibernate.dao.DefaultDao;
import com.pmease.commons.util.StringUtils;
import com.pmease.gitplex.core.entity.Depot;
import com.pmease.gitplex.core.entity.Membership;
import com.pmease.gitplex.core.entity.Team;
import com.pmease.gitplex.core.entity.User;
import com.pmease.gitplex.core.entity.component.IntegrationPolicy;
import com.pmease.gitplex.core.extensionpoint.LifecycleListener;
import com.pmease.gitplex.core.gatekeeper.GateKeeper;
import com.pmease.gitplex.core.manager.DepotManager;
import com.pmease.gitplex.core.manager.UserManager;
import com.pmease.gitplex.core.permission.operation.DepotOperation;

@Singleton
public class DefaultUserManager extends DefaultDao implements UserManager, LifecycleListener {

    private final DepotManager repositoryManager;
    
    private final ReadWriteLock idLock = new ReentrantReadWriteLock();
    		
	private final Map<String, Set<Long>> emailToIds = new HashMap<>();
	
	private final BiMap<String, Long> nameToId = HashBiMap.create();
	
	@Inject
    public DefaultUserManager(Provider<Session> sessionProvider, DepotManager repositoryManager) {
        super(sessionProvider);
        
        this.repositoryManager = repositoryManager;
    }

    @Transactional
    @Override
	public void save(User user) {
    	boolean isNew;
    	if (user.isRoot()) {
    		isNew = get(User.class, User.ROOT_ID) == null;
    		getSession().replicate(user, ReplicationMode.OVERWRITE);
    	} else {
    		isNew = user.isNew();
    		persist(user);
    	}
    	
    	if (isNew) {
        	Team team = new Team();
        	team.setOwner(user);
        	team.setAuthorizedOperation(DepotOperation.NO_ACCESS);
        	team.setName(Team.ANONYMOUS);
        	persist(team);
        	
        	team = new Team();
        	team.setOwner(user);
        	team.setName(Team.LOGGEDIN);
        	team.setAuthorizedOperation(DepotOperation.NO_ACCESS);
        	persist(team);
        	
        	team = new Team();
        	team.setOwner(user);
        	team.setName(Team.OWNERS);
        	team.setAuthorizedOperation(DepotOperation.ADMIN);
        	persist(team);
        	
        	Membership membership = new Membership();
        	membership.setTeam(team);
        	membership.setUser(user);
        	persist(membership);
    	}
    	
    	afterCommit(new Runnable() {

			@Override
			public void run() {
				idLock.writeLock().lock();
				try {
					Set<Long> ids = emailToIds.get(user.getEmail());
					if (ids == null) {
						ids = new HashSet<>();
						emailToIds.put(user.getEmail(), ids);
					}
					ids.add(user.getId());
					if (isNew)
						nameToId.inverse().put(user.getId(), user.getName());
				} finally {
					idLock.writeLock().unlock();
				}
			}
    		
    	});
    }
    
    @Sessional
    @Override
    public User getRoot() {
    	return load(User.class, User.ROOT_ID);
    }

    @Transactional
    @Override
	public void delete(final User user) {
    	Query query = getSession().createQuery("update PullRequest set submitter=null where submitter=:submitter");
    	query.setParameter("submitter", user);
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequest set assignee.id=:rootId where assignee=:assignee");
    	query.setParameter("rootId", User.ROOT_ID);
    	query.setParameter("assignee", user);
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequest set closeInfo.closedBy=null where closeInfo.closedBy=:closedBy");
    	query.setParameter("closedBy", user);
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequestActivity set user=null where user=:user");
    	query.setParameter("user", user);
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequestActivity set user=null where user=:user");
    	query.setParameter("user", user);
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update Comment set user=null where user=:user");
    	query.setParameter("user", user);
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update CommentReply set user=null where user=:user");
    	query.setParameter("user", user);
    	query.executeUpdate();
    	
    	for (Depot depot: user.getDepots())
    		repositoryManager.delete(depot);
    	
		remove(user);
		
		for (Depot each: allOf(Depot.class)) {
			for (Iterator<IntegrationPolicy> it = each.getIntegrationPolicies().iterator(); it.hasNext();) {
				if (it.next().onUserDelete(user))
					it.remove();
			}
			for (Iterator<GateKeeper> it = each.getGateKeepers().iterator(); it.hasNext();) {
				if (it.next().onUserDelete(user))
					it.remove();
			}
		}
		
		afterCommit(new Runnable() {

			@Override
			public void run() {
				idLock.writeLock().lock();
				try {
					for (Iterator<Map.Entry<String, Set<Long>>> it = emailToIds.entrySet().iterator(); it.hasNext();) {
						Map.Entry<String, Set<Long>> entry = it.next();
						entry.getValue().remove(user.getId());
						if (entry.getValue().isEmpty())
							it.remove();
					}
					nameToId.inverse().remove(user.getId());
				} finally {
					idLock.writeLock().unlock();
				}
			}
			
		});
	}

	@Sessional
    @Override
    public User findByName(String userName) {
    	idLock.readLock().lock();
    	try {
    		Long id = nameToId.get(userName);
    		if (id != null)
    			return load(User.class, id);
    		else
    			return null;
    	} finally {
    		idLock.readLock().unlock();
    	}
    }

    @Sessional
    @Override
    public User findByPerson(PersonIdent person) {
    	idLock.readLock().lock();
    	try {
    		Set<Long> ids = emailToIds.get(person.getEmailAddress());
    		if (ids != null) {
    			if (ids.size() > 1) {
    				String personName = person.getName().toLowerCase();
    				int minDistance = Integer.MAX_VALUE;
    				User minDistanceUser = null;
	    			for (Long id: ids) {
	    				User user = load(User.class, id);
	    				int distance;
	    				if (user.getFullName() != null) {
	    					int distance1 = StringUtils.calcLevenshteinDistance(personName, user.getFullName().toLowerCase());
	    					if (distance1 == 0)
	    						return user;
	    					int distance2 = StringUtils.calcLevenshteinDistance(personName, user.getName().toLowerCase());
	    					if (distance2 == 0)
	    						return user;
	    					distance = distance1<distance2?distance1:distance2;
	    				}  else {
	    					distance = StringUtils.calcLevenshteinDistance(personName, user.getName().toLowerCase());
	    					if (distance == 0)
	    						return user;
	    				}
	    				if (distance<minDistance) {
	    					distance = minDistance;
	    					minDistanceUser = user;
	    				}
	    			}
	    			return Preconditions.checkNotNull(minDistanceUser);
    			} else {
    				return load(User.class, ids.iterator().next());
    			}
    		} else {
    			return null;
    		}
    	} finally {
    		idLock.readLock().unlock();
    	}
    }
    
    @Override
	public User getCurrent() {
		Long userId = User.getCurrentId();
		if (userId != 0L) {
			User user = get(User.class, userId);
			if (user != null)
				return user;
		}
		return null;
	}

	@Override
	public User getPrevious() {
		Long userId = User.getPreviousId();
		if (userId != 0L) {
			User user = get(User.class, userId);
			if (user != null)
				return user;
		}
		return null;
	}

	@Sessional
	@Override
	public void systemStarting() {
        for (User user: allOf(User.class)) {
        	Set<Long> ids = emailToIds.get(user.getEmail());
        	if (ids == null) {
        		ids = new HashSet<>();
        		emailToIds.put(user.getEmail(), ids);
        	}
        	ids.add(user.getId());
        	
        	nameToId.inverse().put(user.getId(), user.getName());
        }
	}

	@Override
	public void systemStarted() {
	}

	@Override
	public void systemStopping() {
	}

	@Override
	public void systemStopped() {
	}

	@Transactional
	@Override
	public void rename(Long userId, String oldName, String newName) {
		Query query = getSession().createQuery("update User set name=:newName where name=:oldName");
		query.setParameter("oldName", oldName);
		query.setParameter("newName", newName);
		query.executeUpdate();
		
		for (Depot depot: allOf(Depot.class)) {
			for (IntegrationPolicy integrationPolicy: depot.getIntegrationPolicies()) {
				integrationPolicy.onUserRename(oldName, newName);
			}
			for (GateKeeper gateKeeper: depot.getGateKeepers()) {
				gateKeeper.onUserRename(oldName, newName);
			}
		}
		
    	afterCommit(new Runnable() {

			@Override
			public void run() {
				idLock.writeLock().lock();
				try {
					nameToId.inverse().put(userId, newName);
				} finally {
					idLock.writeLock().unlock();
				}
			}
    		
    	});
	}

}