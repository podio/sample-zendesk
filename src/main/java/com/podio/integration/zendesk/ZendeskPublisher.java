package com.podio.integration.zendesk;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import com.podio.BaseAPI;
import com.podio.comment.Comment;
import com.podio.comment.CommentAPI;
import com.podio.comment.CommentCreate;
import com.podio.common.Reference;
import com.podio.common.ReferenceType;
import com.podio.contact.ContactAPI;
import com.podio.contact.ProfileField;
import com.podio.contact.ProfileType;
import com.podio.file.FileAPI;
import com.podio.item.FieldValues;
import com.podio.item.ItemAPI;
import com.podio.item.ItemBadge;
import com.podio.item.ItemCreate;
import com.podio.item.ItemCreateResponse;
import com.podio.item.ItemUpdate;
import com.podio.item.ItemsResponse;
import com.podio.oauth.OAuthClientCredentials;
import com.podio.oauth.OAuthUsernameCredentials;
import com.podio.user.UserMini;
import com.podio.zendesk.APIFactory;
import com.podio.zendesk.attachment.Attachment;
import com.podio.zendesk.ticket.Ticket;
import com.podio.zendesk.ticket.TicketComment;
import com.podio.zendesk.ticket.TicketFieldEntry;
import com.podio.zendesk.user.User;
import com.sun.jersey.api.client.UniformInterfaceException;

public class ZendeskPublisher {

	private static final int VIEW_ALL = 47845;
	private static final int VIEW_UPDATED_LAST_HOUR = 1992333;

	private static final int SPACE_ID = 208;

	private static final int TICKET_APP_ID = 13731;
	private static final int TICKET_TITLE = 74721;
	private static final int TICKET_DESCRIPTION = 74722;
	private static final int TICKET_FROM = 74759;
	private static final int TICKET_ASSIGNEE = 74723;
	private static final int TICKET_REQUESTER = 74743;
	private static final int TICKET_TYPE = 74727;
	private static final int TICKET_STATUS = 74760;
	private static final int TICKET_SOURCE = 74728;
	private static final int TICKET_LINK = 74758;

	private static final int REQUESTER_APP_ID = 13734;
	private static final int REQUESTER_NAME = 74739;
	private static final int REQUESTER_MAIL = 74740;
	private static final int REQUESTER_PHONE = 74741;
	private static final int REQUESTER_PHOTO = 74742;

	private static final Pattern SUBMITTED_FROM_PATTERN = Pattern
			.compile("Submitted from: (.*)");

	private static final Map<String, String> TYPE_MAP = new HashMap<String, String>();
	static {
		TYPE_MAP.put("question", "Question");
		TYPE_MAP.put("bug", "Bug");
		TYPE_MAP.put("freq", "Feature request");
		TYPE_MAP.put("feature_request", "Feature request");
	}

	private final APIFactory zendeskAPI;

	private final BaseAPI podioAPI;

	private ZendeskPublisher(String configFile) throws IOException {
		super();

		Properties properties = new Properties();
		properties.load(new FileInputStream(configFile));

		this.zendeskAPI = new APIFactory(
				properties.getProperty("zendesk.domain"),
				Boolean.parseBoolean(properties.getProperty("zendesk.ssl")),
				properties.getProperty("zendesk.username"),
				properties.getProperty("zendesk.password"));
		this.podioAPI = new BaseAPI(properties.getProperty("podio.api"),
				properties.getProperty("podio.upload"), 443, true, false,
				new OAuthClientCredentials(properties
						.getProperty("podio.client.mail"), properties
						.getProperty("podio.client.secret")),
				new OAuthUsernameCredentials(properties
						.getProperty("podio.user.mail"), properties
						.getProperty("podio.user.password")));
	}

	private ItemBadge getPodioTicket(int ticketId) {
		ItemsResponse response = new ItemAPI(podioAPI).getItemsByExternalId(
				TICKET_APP_ID, Integer.toString(ticketId));
		if (response.getFiltered() < 1) {
			return null;
		}

		return response.getItems().get(0);
	}

	private ItemBadge getPodioRequester(int requesterId) {
		ItemsResponse response = new ItemAPI(podioAPI).getItemsByExternalId(
				REQUESTER_APP_ID, Integer.toString(requesterId));
		if (response.getFiltered() < 1) {
			return null;
		}

		return response.getItems().get(0);
	}

	private UserMini findPodioUser(int zendeskUserId) {
		User user = getZendeskUser(zendeskUserId);
		if (user == null) {
			return null;
		}

		if (user.getEmail() != null) {
			List<UserMini> users = new ContactAPI(podioAPI).getSpaceContacts(
					SPACE_ID, ProfileField.MAIL, user.getEmail().toLowerCase(),
					null, null, ProfileType.MINI, null);
			if (users.size() == 1) {
				return users.get(0);
			}
		}

		if (user.getName() != null) {
			List<UserMini> users = new ContactAPI(podioAPI).getSpaceContacts(
					SPACE_ID, ProfileField.NAME, user.getName(), null, null,
					ProfileType.MINI, null);
			if (users.size() == 1) {
				return users.get(0);
			}
		}

		return null;
	}

	private User getZendeskUser(int zendeskUserId) {
		try {
			return zendeskAPI.getUserAPI().getUser(zendeskUserId);
		} catch (UniformInterfaceException e) {
			e.printStackTrace();
			return null;
		}
	}

	private List<FieldValues> getRequesterFields(User user, boolean uploadImage)
			throws IOException {
		List<FieldValues> fields = new ArrayList<FieldValues>();
		fields.add(new FieldValues(REQUESTER_NAME, "value", StringUtils
				.abbreviate(user.getName(), 128)));
		if (user.getEmail() != null) {
			fields.add(new FieldValues(REQUESTER_MAIL, "value", user.getEmail()));
		}
		if (user.getPhone() != null) {
			fields.add(new FieldValues(REQUESTER_PHONE, "value", user
					.getPhone()));
		}
		if (user.getPhotoURL() != null && uploadImage
				&& !user.getPhotoURL().getFile().endsWith("user_sm.png")) {
			Integer photoImageId = upload(user.getPhotoURL(), null, null);
			if (photoImageId != null) {
				fields.add(new FieldValues(REQUESTER_PHOTO, "value",
						photoImageId));
			}
		}

		return fields;
	}

	private List<FieldValues> getTicketFields(Ticket ticket) throws IOException {
		List<FieldValues> fields = new ArrayList<FieldValues>();
		fields.add(new FieldValues(TICKET_TITLE, "value", StringUtils
				.abbreviate(ticket.getDescription(), 128)));
		fields.add(new FieldValues(TICKET_DESCRIPTION, "value", ticket
				.getDescription()));

		Matcher matcher = SUBMITTED_FROM_PATTERN.matcher(ticket
				.getDescription());
		if (matcher.find()) {
			String from = matcher.group(1);
			if (from != null) {
				fields.add(new FieldValues(TICKET_FROM, "value", from));
			}
		}

		if (ticket.getAssigneeId() != null) {
			UserMini podioUser = findPodioUser(ticket.getAssigneeId());
			if (podioUser != null) {
				fields.add(new FieldValues(TICKET_ASSIGNEE, "value", podioUser
						.getId()));
			}
		}

		Integer podioRequesterId = updateRequester(ticket.getRequesterId());
		if (podioRequesterId != null) {
			fields.add(new FieldValues(TICKET_REQUESTER, "value",
					podioRequesterId));
		}
		for (TicketFieldEntry entry : ticket.getEntries()) {
			if (entry.getFieldId() == 87154
					&& StringUtils.isNotBlank(entry.getValue())) {
				String podioValue = TYPE_MAP.get(entry.getValue());
				if (podioValue != null) {
					fields.add(new FieldValues(TICKET_TYPE, "value", podioValue));
				} else {
					System.out.println("Unknown type " + entry.getValue());
				}
			}
		}
		fields.add(new FieldValues(TICKET_STATUS, "value", toPodioState(ticket
				.getStatus())));
		if (ticket.getVia() != null) {
			fields.add(new FieldValues(TICKET_SOURCE, "value",
					toPodioState(ticket.getVia())));
		}
		fields.add(new FieldValues(TICKET_LINK, "value",
				"http://hoist.zendesk.com/tickets/" + ticket.getId()));

		return fields;
	}

	private String toPodioState(Enum<?> en) {
		String name = en.name().toLowerCase();
		name = name.replace('_', ' ');
		return StringUtils.capitalize(name);
	}

	private Integer updateRequester(int requesterZendeskId) throws IOException {
		User requesterZendesk = getZendeskUser(requesterZendeskId);
		if (requesterZendesk == null) {
			return null;
		}

		ItemBadge requesterPodio = getPodioRequester(requesterZendeskId);
		if (requesterPodio != null) {
			if (requesterPodio.getCurrentRevision().getCreatedOn()
					.isBefore(requesterZendesk.getUpdatedAt())) {
				List<FieldValues> fields = getRequesterFields(requesterZendesk,
						false);

				new ItemAPI(podioAPI).updateItem(requesterPodio.getId(),
						new ItemUpdate(Integer.toString(requesterZendeskId),
								fields), true);
			}

			return requesterPodio.getId();
		} else {
			List<FieldValues> fields = getRequesterFields(requesterZendesk,
					true);

			ItemCreateResponse response = new ItemAPI(podioAPI).addItem(
					REQUESTER_APP_ID,
					new ItemCreate(Integer.toString(requesterZendeskId),
							fields, Collections.<Integer> emptyList(),
							Collections.<String> emptyList()), true);
			return response.getItemId();
		}
	}

	public void updateTickets() throws IOException {
		int page = 1;
		while (true) {
			List<Ticket> tickets = zendeskAPI.getTicketAPI().getTickets(
					VIEW_ALL, page);
			System.out.println("Doing page " + page + " with " + tickets.size()
					+ " tickets");
			for (Ticket ticket : tickets) {
				updateTicket(ticket);
			}

			page++;

			if (tickets.size() < 30) {
				return;
			}
		}
	}

	private void updateTicket(int ticketId) throws IOException {
		Ticket ticketZendesk = zendeskAPI.getTicketAPI().getTicket(ticketId);

		updateTicket(ticketZendesk);
	}

	private void updateTicket(Ticket ticketZendesk) throws IOException {
		ItemBadge ticketPodio = getPodioTicket(ticketZendesk.getId());
		if (ticketPodio != null) {
			boolean before = ticketPodio.getCurrentRevision().getCreatedOn()
					.isBefore(ticketZendesk.getUpdatedAt());
			if (before) {
				List<FieldValues> fields = getTicketFields(ticketZendesk);

				new ItemAPI(podioAPI).updateItem(ticketPodio.getId(),
						new ItemUpdate(Integer.toString(ticketZendesk.getId()),
								fields), true);

				// TagAPI tagAPI = new TagAPI(podioAPI);
				// FIXME: Update tags

				List<Comment> commentsPodio = new CommentAPI(podioAPI)
						.getComments(new Reference(ReferenceType.ITEM,
								ticketPodio.getId()));
				for (TicketComment commentZendesk : ticketZendesk.getComments()) {
					boolean found = false;
					for (Comment commentPodio : commentsPodio) {
						if (commentPodio.getValue().contains(
								commentZendesk.getValue())) {
							found = true;
						}
					}

					if (!found) {
						uploadComment(ticketPodio.getId(),
								ticketZendesk.getRequesterId(), commentZendesk);
					}
				}
			}
		} else {
			List<FieldValues> fields = getTicketFields(ticketZendesk);

			ItemCreateResponse response = new ItemAPI(podioAPI).addItem(
					TICKET_APP_ID,
					new ItemCreate(Integer.toString(ticketZendesk.getId()),
							fields, Collections.<Integer> emptyList(),
							ticketZendesk.getCurrentTags()), true);
			int ticketIdPodio = response.getItemId();

			for (TicketComment comment : ticketZendesk.getComments()) {
				uploadComment(ticketIdPodio, ticketZendesk.getRequesterId(),
						comment);
			}
		}
	}

	private void uploadComment(int ticketIdPodio, int requesterIdZendesk,
			TicketComment commentZendesk) throws IOException {
		String commentText = commentZendesk.getValue();
		commentText += "<br /><br />";
		if (requesterIdZendesk == commentZendesk.getAuthorId()) {
			ItemBadge requesterPodio = getPodioRequester(commentZendesk
					.getAuthorId());
			if (requesterPodio != null) {
				commentText += "<a href=\"" + requesterPodio.getLink() + "\">"
						+ requesterPodio.getTitle() + "</a>";
			} else {
				commentText += "Unknown";
			}
		} else {
			UserMini podioUser = findPodioUser(commentZendesk.getAuthorId());
			if (podioUser != null) {
				commentText += "<a href=\"https://podio.com/contacts/"
						+ podioUser.getId() + "\">" + podioUser.getName()
						+ "</a>";
			} else {
				User zendeskUser = getZendeskUser(commentZendesk.getAuthorId());
				commentText += "<a href=\"mailto:" + zendeskUser.getEmail()
						+ "\">" + zendeskUser.getName() + "</a>";
			}
		}

		List<Integer> fileIds = uploadAttachment(
				commentZendesk.getAttachments(), null);

		new CommentAPI(podioAPI).addComment(new Reference(ReferenceType.ITEM,
				ticketIdPodio), new CommentCreate(commentText, null,
				Collections.<Integer> emptyList(), fileIds), true);
	}

	private List<Integer> uploadAttachment(List<Attachment> attachments,
			Reference reference) throws IOException {
		List<Integer> fileIds = new ArrayList<Integer>();
		for (Attachment attachment : attachments) {
			URL url = new URL("http://hoist.zendesk.com/attachments/token/"
					+ attachment.getToken() + "/?name=image001.png");

			Integer fileId = upload(url, attachment.getFilename(), reference);
			if (fileId != null) {
				fileIds.add(fileId);
			}
		}

		return fileIds;
	}

	private Integer upload(URL url, String name, Reference reference)
			throws IOException {
		try {
			return new FileAPI(podioAPI).uploadImage(url, name, reference);
		} catch (FileNotFoundException e) {
			System.err.println(url + " was not found!");
			return null;
		}
	}

	public static void main(String[] args) throws IOException {
		new ZendeskPublisher("config.properties").updateTickets();
	}
}
