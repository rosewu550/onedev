package io.onedev.server.web.page.project.pullrequests.detail.activities;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.Cookie;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.Component;
import org.apache.wicket.RestartResponseAtInterceptPageException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.form.AjaxCheckBox;
import org.apache.wicket.ajax.markup.html.form.AjaxSubmitLink;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.http.WebRequest;
import org.apache.wicket.request.http.WebResponse;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.joda.time.DateTime;

import com.google.common.collect.Lists;

import de.agilecoders.wicket.core.markup.html.bootstrap.common.NotificationPanel;
import io.onedev.server.OneDev;
import io.onedev.server.manager.CodeCommentManager;
import io.onedev.server.manager.IssueManager;
import io.onedev.server.manager.PullRequestCommentManager;
import io.onedev.server.manager.PullRequestManager;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestChange;
import io.onedev.server.model.PullRequestComment;
import io.onedev.server.model.PullRequestUpdate;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestReferencedFromCodeCommentData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestReferencedFromIssueData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestReferencedFromPullRequestData;
import io.onedev.server.web.component.markdown.AttachmentSupport;
import io.onedev.server.web.component.project.comment.CommentInput;
import io.onedev.server.web.component.user.avatar.UserAvatarLink;
import io.onedev.server.web.page.project.pullrequests.detail.PullRequestDetailPage;
import io.onedev.server.web.page.project.pullrequests.detail.activities.activity.PullRequestChangeActivity;
import io.onedev.server.web.page.project.pullrequests.detail.activities.activity.PullRequestCommentedActivity;
import io.onedev.server.web.page.project.pullrequests.detail.activities.activity.PullRequestOpenedActivity;
import io.onedev.server.web.page.project.pullrequests.detail.activities.activity.PullRequestUpdatedActivity;
import io.onedev.server.web.page.security.LoginPage;
import io.onedev.server.web.util.DeleteCallback;
import io.onedev.server.web.util.ProjectAttachmentSupport;
import io.onedev.server.web.websocket.PageDataChanged;

@SuppressWarnings("serial")
public class PullRequestActivitiesPage extends PullRequestDetailPage {
	
	private static final String COOKIE_SHOW_COMMENTS = "onedev.server.pullRequest.showComments";
	
	private static final String COOKIE_SHOW_COMMITS = "onedev.server.pullRequest.showCommits";
	
	private static final String COOKIE_SHOW_CHANGE_HISTORY = "onedev.server.pullRequest.showChangeHistory";
 	
	private boolean showComments = true;
	
	private boolean showCommits = true;
	
	private boolean showChangeHistory = true;
	
	private WebMarkupContainer container;
	
	private RepeatingView activitiesView;
	
	public PullRequestActivitiesPage(PageParameters params) {
		super(params);

		WebRequest request = (WebRequest) RequestCycle.get().getRequest();
		Cookie cookie = request.getCookie(COOKIE_SHOW_COMMENTS);
		if (cookie != null)
			showComments = Boolean.valueOf(cookie.getValue());
		
		cookie = request.getCookie(COOKIE_SHOW_COMMITS);
		if (cookie != null)
			showCommits = Boolean.valueOf(cookie.getValue());
		
		cookie = request.getCookie(COOKIE_SHOW_CHANGE_HISTORY);
		if (cookie != null)
			showChangeHistory = Boolean.valueOf(cookie.getValue());
	}
	
	private Component newActivityRow(String id, PullRequestActivity activity) {
		WebMarkupContainer row = new WebMarkupContainer(id, Model.of(activity));
		row.setOutputMarkupId(true);
		
		String anchor = activity.getAnchor();
		if (anchor != null)
			row.setMarkupId(anchor);
		
		row.add(new UserAvatarLink("avatar", activity.getUser()));
		
		Component content = activity.render("content", new DeleteCallback() {

			@Override
			public void onDelete(AjaxRequestTarget target) {
				row.remove();
				target.appendJavaScript(String.format("$('#%s').remove();", row.getMarkupId()));
			}
			
		});
		
		row.add(content);
		
		row.add(AttributeAppender.append("class", activity.getClass().getSimpleName()));
		
		return row;
	}
	
	private List<PullRequestActivity> getActivities() {
		PullRequest request = getPullRequest();
		List<PullRequestActivity> activities = new ArrayList<>();

		activities.add(new PullRequestOpenedActivity(request));
		
		List<PullRequestActivity> otherActivities = new ArrayList<>();
		if (showCommits) {
			for (PullRequestUpdate update: request.getUpdates())
				otherActivities.add(new PullRequestUpdatedActivity(update));
		}

		if (showComments) {
			for (PullRequestComment comment: request.getComments()) { 
				otherActivities.add(new PullRequestCommentedActivity(comment));
			}
		}
		
		if (showChangeHistory) {
			for (PullRequestChange change: request.getChanges()) {
				if (change.getData() instanceof PullRequestReferencedFromIssueData) {
					PullRequestReferencedFromIssueData referencedFromIssueData = (PullRequestReferencedFromIssueData) change.getData();
					if (OneDev.getInstance(IssueManager.class).get(referencedFromIssueData.getIssueId()) != null)
						otherActivities.add(new PullRequestChangeActivity(change));
				} else if (change.getData() instanceof PullRequestReferencedFromPullRequestData) {
					PullRequestReferencedFromPullRequestData referencedFromPullRequestData = (PullRequestReferencedFromPullRequestData) change.getData();
					if (OneDev.getInstance(PullRequestManager.class).get(referencedFromPullRequestData.getRequestId()) != null)
						otherActivities.add(new PullRequestChangeActivity(change));
				} else if (change.getData() instanceof PullRequestReferencedFromCodeCommentData) {
					PullRequestReferencedFromCodeCommentData referencedFromCodeCommentData = (PullRequestReferencedFromCodeCommentData) change.getData();
					if (OneDev.getInstance(CodeCommentManager.class).get(referencedFromCodeCommentData.getCommentId()) != null)
						otherActivities.add(new PullRequestChangeActivity(change));
				} else {
					otherActivities.add(new PullRequestChangeActivity(change));
				}
			}
		}
		
		otherActivities.sort((o1, o2) -> {
			if (o1.getDate().getTime()<o2.getDate().getTime())
				return -1;
			else if (o1.getDate().getTime()>o2.getDate().getTime())
				return 1;
			else if (o1 instanceof PullRequestOpenedActivity)
				return -1;
			else
				return 1;
		});
		
		activities.addAll(otherActivities);
		
		return activities;
	}
	
	@Override
	public void onEvent(IEvent<?> event) {
		super.onEvent(event);

		if (event.getPayload() instanceof PageDataChanged) {
			PageDataChanged pageDataChanged = (PageDataChanged) event.getPayload();
			IPartialPageRequestHandler partialPageRequestHandler = pageDataChanged.getHandler();

			@SuppressWarnings("deprecation")
			Component prevActivityRow = activitiesView.get(activitiesView.size()-1);
			PullRequestActivity lastActivity = (PullRequestActivity) prevActivityRow.getDefaultModelObject();
			List<PullRequestActivity> newActivities = new ArrayList<>();
			for (PullRequestActivity activity: getActivities()) {
				if (activity.getDate().getTime() > lastActivity.getDate().getTime())
					newActivities.add(activity);
			}

			Component sinceChangesRow = null;
			for (Component row: activitiesView) {
				if (row.getDefaultModelObject() == null) {
					sinceChangesRow = row;
					break;
				}
			}

			if (sinceChangesRow == null && !newActivities.isEmpty()) {
				Date sinceDate = new DateTime(newActivities.iterator().next().getDate()).minusSeconds(1).toDate();
				sinceChangesRow = newSinceChangesRow(activitiesView.newChildId(), sinceDate);
				activitiesView.add(sinceChangesRow);
				String script = String.format("$(\"<tr id='%s'></tr>\").insertAfter('#%s');", 
						sinceChangesRow.getMarkupId(), prevActivityRow.getMarkupId());
				partialPageRequestHandler.prependJavaScript(script);
				partialPageRequestHandler.add(sinceChangesRow);
				prevActivityRow = sinceChangesRow;
			}
			
			for (PullRequestActivity activity: newActivities) {
				Component newActivityRow = newActivityRow(activitiesView.newChildId(), activity); 
				newActivityRow.add(AttributeAppender.append("class", "new"));
				activitiesView.add(newActivityRow);
				
				String script = String.format("$(\"<tr id='%s'></tr>\").insertAfter('#%s');", 
						newActivityRow.getMarkupId(), prevActivityRow.getMarkupId());
				partialPageRequestHandler.prependJavaScript(script);
				partialPageRequestHandler.add(newActivityRow);
				
				if (activity instanceof PullRequestUpdatedActivity) {
					partialPageRequestHandler.appendJavaScript("$('tr.since-changes').addClass('visible');");
				}
				prevActivityRow = newActivityRow;
			}
		}
	}

	private Component newSinceChangesRow(String id, Date sinceDate) {
		WebMarkupContainer row = new WebMarkupContainer(id);
		row.setOutputMarkupId(true);
		
		row.add(new WebMarkupContainer("avatar"));
		WebMarkupContainer contentColumn = new Fragment("content", "sinceChangesRowContentFrag", this);
		contentColumn.add(AttributeAppender.append("colspan", "2"));
		contentColumn.add(new SinceChangesLink("sinceChanges", requestModel, sinceDate));
		row.add(contentColumn);
		
		row.add(AttributeAppender.append("class", "since-changes"));
		
		return row;
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(container = new WebMarkupContainer("activities") {

			@Override
			protected void onBeforeRender() {
				WebMarkupContainer container = this;
				addOrReplace(new AjaxCheckBox("showComments", Model.of(showComments)) {

					@Override
					protected void onUpdate(AjaxRequestTarget target) {
						showComments = !showComments;
						WebResponse response = (WebResponse) RequestCycle.get().getResponse();
						Cookie cookie = new Cookie(COOKIE_SHOW_COMMENTS, String.valueOf(showComments));
						cookie.setPath("/");
						cookie.setMaxAge(Integer.MAX_VALUE);
						response.addCookie(cookie);
						target.add(container);
					}
					
				});
				
				addOrReplace(new AjaxCheckBox("showCommits", Model.of(showCommits)) {

					@Override
					protected void onUpdate(AjaxRequestTarget target) {
						showCommits = !showCommits;
						WebResponse response = (WebResponse) RequestCycle.get().getResponse();
						Cookie cookie = new Cookie(COOKIE_SHOW_COMMITS, String.valueOf(showCommits));
						cookie.setPath("/");
						cookie.setMaxAge(Integer.MAX_VALUE);
						response.addCookie(cookie);
						target.add(container);
					}

				});
				
				addOrReplace(new AjaxCheckBox("showChangeHistory", Model.of(showChangeHistory)) {

					@Override
					protected void onUpdate(AjaxRequestTarget target) {
						showChangeHistory = !showChangeHistory;
						WebResponse response = (WebResponse) RequestCycle.get().getResponse();
						Cookie cookie = new Cookie(COOKIE_SHOW_CHANGE_HISTORY, String.valueOf(showChangeHistory));
						cookie.setPath("/");
						cookie.setMaxAge(Integer.MAX_VALUE);
						response.addCookie(cookie);
						target.add(container);
					}

				});
				
				addOrReplace(activitiesView = new RepeatingView("activities"));
				
				List<PullRequestActivity> activities = getActivities();

				PullRequest request = getPullRequest();
				List<PullRequestActivity> oldActivities = new ArrayList<>();
				List<PullRequestActivity> newActivities = new ArrayList<>();
				for (PullRequestActivity activity: activities) {
					if (request.isVisitedAfter(activity.getDate())) {
						oldActivities.add(activity);
					} else {
						newActivities.add(activity);
					}
				}

				for (PullRequestActivity activity: oldActivities) {
					activitiesView.add(newActivityRow(activitiesView.newChildId(), activity));
				}

				if (!oldActivities.isEmpty() && !newActivities.isEmpty()) {
					Date sinceDate = new DateTime(newActivities.iterator().next().getDate()).minusSeconds(1).toDate();
					Component row = newSinceChangesRow(activitiesView.newChildId(), sinceDate);
					for (PullRequestActivity activity: newActivities) {
						if (activity instanceof PullRequestUpdatedActivity) {
							row.add(AttributeAppender.append("class", "visible"));
							break;
						}
					}
					activitiesView.add(row);
				}
				
				for (PullRequestActivity activity: newActivities) {
					Component row = newActivityRow(activitiesView.newChildId(), activity);
					row.add(AttributeAppender.append("class", "new"));
					activitiesView.add(row);
				}
				
				super.onBeforeRender();
			}
			
		});
		container.setOutputMarkupId(true);
				
		if (getLoginUser() != null) {
			Fragment fragment = new Fragment("addComment", "addCommentFrag", this);
			fragment.setOutputMarkupId(true);

			Form<?> form = new Form<Void>("form");
			fragment.add(form);
			
			CommentInput input = new CommentInput("input", Model.of(""), false) {

				@Override
				protected AttachmentSupport getAttachmentSupport() {
					return new ProjectAttachmentSupport(getProject(), getPullRequest().getUUID());
				}

				@Override
				protected Project getProject() {
					return PullRequestActivitiesPage.this.getProject();
				}
				
				@Override
				protected List<AttributeModifier> getInputModifiers() {
					return Lists.newArrayList(AttributeModifier.replace("placeholder", "Leave a comment"));
				}
				
			};
			input.setRequired(true).setLabel(Model.of("Comment"));
			form.add(input);
			
			form.add(new NotificationPanel("feedback", input));
			
			form.add(new AjaxSubmitLink("save") {

				@Override
				protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
					super.onSubmit(target, form);

					PullRequestComment comment = new PullRequestComment();
					comment.setRequest(getPullRequest());
					comment.setUser(getLoginUser());
					comment.setContent(input.getModelObject());
					OneDev.getInstance(PullRequestCommentManager.class).save(comment);
					input.setModelObject("");

					target.add(fragment);
					
					@SuppressWarnings("deprecation")
					Component lastActivityRow = activitiesView.get(activitiesView.size()-1);
					Component newActivityRow = newActivityRow(activitiesView.newChildId(), new PullRequestCommentedActivity(comment)); 
					activitiesView.add(newActivityRow);
					
					String script = String.format("$(\"<tr id='%s'></tr>\").insertAfter('#%s');", 
							newActivityRow.getMarkupId(), lastActivityRow.getMarkupId());
					target.prependJavaScript(script);
					target.add(newActivityRow);
				}

				@Override
				protected void onError(AjaxRequestTarget target, Form<?> form) {
					super.onError(target, form);
					target.add(form);
				}

			});
			container.add(fragment);
		} else {
			Fragment fragment = new Fragment("addComment", "loginToCommentFrag", this);
			fragment.add(new Link<Void>("login") {

				@Override
				public void onClick() {
					throw new RestartResponseAtInterceptPageException(LoginPage.class);
				}
				
			});
			container.add(fragment);
		}
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new PullRequestActivitiesCssResourceReference()));
	}
	
}
