package no.javazone.cake.redux;

import no.javazone.cake.redux.comments.FeedbackService;
import no.javazone.cake.redux.mail.MailSenderImplementation;
import no.javazone.cake.redux.mail.MailSenderService;
import no.javazone.cake.redux.mail.MailToSend;
import no.javazone.cake.redux.sleepingpill.SleepingpillCommunicator;
import no.javazone.cake.redux.sleepingpill.SlotUpdaterService;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.jsonbuddy.JsonArray;
import org.jsonbuddy.JsonFactory;
import org.jsonbuddy.JsonNull;
import org.jsonbuddy.JsonObject;
import org.jsonbuddy.parse.JsonParser;
import org.jsonbuddy.pojo.PojoMapper;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class DataServlet extends HttpServlet {
    private SleepingpillCommunicator sleepingpillCommunicator;
    private AcceptorSetter acceptorSetter;
    private UserFeedbackCommunicator userFeedbackCommunicator;

    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String pathInfo = req.getPathInfo();
        if ("/editTalk".equals(pathInfo)) {
            updateTalk(req, resp);
        } else if ("/acceptTalks".equals(pathInfo)) {
            acceptTalks(req,resp);
        } else if ("/massUpdate".equals(pathInfo)) {
            massUpdate(req, resp);
        } else if ("/assignRoom".equals(pathInfo)) {
            assignRoom(req,resp);
        } else if ("/assignSlot".equals(pathInfo)) {
            assignSlot(req,resp);
        } else if ("/massPublish".equals(pathInfo)) {
            massPublish(req, resp);
        } else if ("/addComment".equals(pathInfo)) {
            addComment(req, resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/giveRating".equals(pathInfo)) {
            giveRating(req, resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/addPubComment".equals(pathInfo)) {
            addPublicComment(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/publishChanges".equals(pathInfo)) {
            publishChanges(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/updateroomslot".equals(pathInfo)) {
            updateRoomSlot(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/smartTimeUpdate".equals(pathInfo)) {
            smartUpdateTime(req,resp);
            resp.setContentType("application/json;charset=UTF-8");

        } else if ("/sendForRoomSlotUpdate".equals(pathInfo)) {
            roomSlotUpdate(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        }

    }

    private void roomSlotUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        SlotUpdaterService.get().updateRoomSlot(update,computeAccessType(req));
        JsonFactory.jsonObject().toJson(resp.getWriter());
    }


    private void updateRoomSlot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        String talkref = update.requiredString("talkref");
        UserAccessType userAccessType = computeAccessType(req);
        Optional<String> startTime = update.stringValue("starttime");
        if (startTime.isPresent()) {
            sleepingpillCommunicator.updateSlotTime(talkref,startTime.get(), userAccessType);
        }
        Optional<String> room = update.stringValue("room");
        if (room.isPresent()) {
            sleepingpillCommunicator.updateRoom(talkref,room.get(),userAccessType);
        }

        JsonFactory.jsonObject().toJson(resp.getWriter());
    }

    private void smartUpdateTime(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        String talkref = update.requiredString("talkref");
        String day = update.stringValue("smartDay").orElse("");
        String smartTime = update.stringValue("smartTime").orElse("");

        if (day.trim().isEmpty() || smartTime.trim().isEmpty()) {
            JsonFactory.jsonObject().toJson(resp.getWriter());
            return;
        }

        int dayoffset = "Wednesday".equalsIgnoreCase(day) ? 0 : 1;
        int timslot = Integer.parseInt(smartTime)-1;
        LocalDateTime startTime = Configuration.conferenceWednesday().atTime(9,30);

        startTime = startTime.plusDays(dayoffset);
        startTime = startTime.plusMinutes(70L*timslot);

        if (timslot >= 3) {
            startTime = startTime.plusMinutes(30);
        }

        UserAccessType userAccessType = computeAccessType(req);
        sleepingpillCommunicator.updateSlotTime(talkref,startTime,userAccessType);

        JsonFactory.jsonObject().toJson(resp.getWriter());
    }

    private void publishChanges(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        sleepingpillCommunicator.pubishChanges(update.requiredString("talkref"),computeAccessType(req));
        JsonFactory.jsonObject().toJson(resp.getWriter());
    }

    private void addPublicComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        String ref = update.requiredString("talkref");
        String comment = update.requiredString("comment");
        String lastModified = update.requiredString("lastModified");
        JsonObject jsonObject = sleepingpillCommunicator.addPublicComment(ref, comment, lastModified);

        MailToSend simpleEmail = generateCommentEmail(jsonObject,comment);
        MailSenderService.get().sendMail(MailSenderImplementation.create(simpleEmail));


        JsonArray updatedComments = jsonObject.requiredArray("comments");
        updatedComments.toJson(resp.getWriter());




    }

    private MailToSend generateCommentEmail(JsonObject jsonObject, String comment) {
        SimpleEmail simpleEmail = new SimpleEmail();
        List<String> to = jsonObject.requiredArray("speakers").objectStream()
                .map(ob -> ob.requiredString("email"))
                .collect(Collectors.toList());

        String subject = "Regarding your JavaZone submission";
        String content = "Hello,\n\n" +
                "The program committee has added a comment to your submission that requires your attention: " +
                comment;


        return new MailToSend(to,subject,content);
    }

    private void giveRating(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonArray ratings = FeedbackService.get().giveRating(JsonParser.parseToObject(req.getInputStream()), req.getSession().getAttribute("username").toString());
        ratings.toJson(resp.getWriter());
    }

    private void addComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonArray comments = FeedbackService.get().addComment(JsonParser.parseToObject(req.getInputStream()), req.getSession().getAttribute("username").toString());
        comments.toJson(resp.getWriter());
    }

    private void massPublish(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream inputStream = req.getInputStream()) {
            JsonObject update = JsonParser.parseToObject(inputStream);
            String ref = update.requiredString("ref");
            approveTalk(ref,computeAccessType(req));
        }
        JsonObject objectNode = JsonFactory.jsonObject();
        objectNode.put("status","ok");
        resp.setContentType("text/json");
        objectNode.toJson(resp.getWriter());
    }

    private void approveTalk(String ref,UserAccessType userAccessType) throws IOException {
        sleepingpillCommunicator.approveTalk(ref,userAccessType);
    }



    private void assignRoom(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        throw new NotImplementedException();
        /*
        try (InputStream inputStream = req.getInputStream()) {
            String inputStr = CommunicatorHelper.toString(inputStream);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode update = objectMapper.readTree(inputStr);
            String ref = update.get("talkRef").asText();
            String roomRef = update.get("roomRef").asText();

            String lastModified = update.get("lastModified").asText();

            //String newTalk = xemsCommunicator.assignRoom(ref,roomRef,lastModified,computeAccessType(req));
            //resp.getWriter().append(newTalk);
        }*/

    }

    private void assignSlot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        throw new NotImplementedException();
        /*
        try (InputStream inputStream = req.getInputStream()) {
            String inputStr = CommunicatorHelper.toString(inputStream);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode update = objectMapper.readTree(inputStr);

            String ref = update.get("talkRef").asText();
            String slotRef = update.get("slotRef").asText();

            String lastModified = update.get("lastModified").asText();

            String newTalk = emsCommunicator.assignSlot(ref, slotRef, lastModified,computeAccessType(req));
            resp.getWriter().append(newTalk);
        }*/

    }




    private static UserAccessType computeAccessType(HttpServletRequest request) {
        String fullusers = Optional.ofNullable(Configuration.fullUsers()).orElse("");
        if (Optional.ofNullable(request.getSession().getAttribute("username"))
            .filter(un -> fullusers.contains((String) un))
            .isPresent()) {
            return UserAccessType.FULL;
        }
        return UserAccessType.WRITE;
    }

    private static UserWithAccess computeUserWithAccess(HttpServletRequest request) {
        return new UserWithAccess(Optional.ofNullable(request.getSession().getAttribute("username")).map(Object::toString).orElse("Unknown"),computeAccessType(request));
    }

    private void acceptTalks(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream inputStream = req.getInputStream()) {
            JsonObject obj = JsonParser.parseToObject(inputStream);
            JsonArray talks = obj.requiredArray("talks");
            String inputStr = CommunicatorHelper.toString(inputStream);

            String statusJson = acceptorSetter.accept(talks,computeUserWithAccess(req));
            resp.getWriter().append(statusJson);
        }
    }

    private void massUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream inputStream = req.getInputStream()) {
            JsonObject input = JsonParser.parseToObject(inputStream);
            String statusJson = acceptorSetter.massUpdate(input,computeUserWithAccess(req));
            resp.getWriter().append(statusJson);

        }
    }
    private void updateTalk(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }

        String ref = update.requiredString("ref");
        JsonArray tags = (JsonArray) update.value("tags").orElse(JsonFactory.jsonArray());
        JsonArray keywords = (JsonArray) update.value("keywords").orElse(JsonFactory.jsonArray());

        String state = update.requiredString("state");
        String lastModified = update.stringValue("lastModified").orElse("xx");

        List<TagWithAuthor> taglist = tags.objectStream().map(a -> PojoMapper.map(a,TagWithAuthor.class)).collect(Collectors.toList());
        List<String> keywordlist = keywords.strings();

        //String newTalk = emsCommunicator.update(ref, taglist, state, lastModified,computeAccessType(req));
        String newTalk = sleepingpillCommunicator.update(ref, taglist, keywordlist,state, lastModified,computeAccessType(req));
        resp.getWriter().append(newTalk);

    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        String pathInfo = request.getPathInfo();
        if ("/talks".equals(pathInfo)) {
            String encEvent = request.getParameter("eventId");
            String json = sleepingpillCommunicator.talkShortVersion(encEvent);

            writer.append(json);

        } else if ("/atalk".equals(pathInfo)) {
            String encTalk = request.getParameter("talkId");
            JsonObject oneTalkAsJson = sleepingpillCommunicator.oneTalkAsJson(encTalk);
            appendFeedbacks(oneTalkAsJson,encTalk);
            oneTalkAsJson.put("username",request.getSession().getAttribute("username"));
            // TODO Fix feedbacks
            //appendUserFeedback(oneTalkAsJson, userFeedbackCommunicator.feedback(oneTalkAsJson.stringValue("emslocation")));
            appendUserFeedback(oneTalkAsJson, Optional.empty());
            writer.append(SleepingpillCommunicator.jsonHackFix(oneTalkAsJson.toJson()));
        } else if ("/events".equals(pathInfo)) {
            writer.append(sleepingpillCommunicator.allEvents());
        } else if ("/roomsSlots".equals(pathInfo)) {
            String encEvent = request.getParameter("eventId");
            JsonFactory.jsonObject()
                    .put("rooms",JsonFactory.jsonArray())
                    .put("slots",JsonFactory.jsonArray())
                    .toJson(writer);
            //writer.append(emsCommunicator.allRoomsAndSlots(encEvent));
        }
    }

    private void appendUserFeedback(JsonObject oneTalkAsJson, Optional<String> feedback) {
        if (feedback.isPresent()) {
            JsonObject feedbackAsJson = JsonParser.parseToObject(feedback.get());
            oneTalkAsJson.put("userFeedback", feedbackAsJson);
        } else {
            oneTalkAsJson.put("userFeedback", new JsonNull());
        }
    }

    private void appendFeedbacks(JsonObject oneTalkAsJson, String encTalk) {
        JsonArray comments = FeedbackService.get().commentsForTalk(encTalk);
        oneTalkAsJson.put("comments",comments);
        JsonArray ratings = FeedbackService.get().ratingsForTalk(encTalk);
        oneTalkAsJson.put("ratings",ratings);
        Optional<String> contact = FeedbackService.get().contactForTalk(encTalk);
        oneTalkAsJson.put("contactPhone",contact.orElse("Unknown"));
    }
    

    @Override
    public void init() throws ServletException {
        sleepingpillCommunicator = new SleepingpillCommunicator();
        userFeedbackCommunicator = new UserFeedbackCommunicator();
        acceptorSetter = new AcceptorSetter(sleepingpillCommunicator);
    }


    public void setUserFeedbackCommunicator(UserFeedbackCommunicator userFeedbackCommunicator) {
        this.userFeedbackCommunicator = userFeedbackCommunicator;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            super.service(req, resp);
        } catch (NoUserAceessException ex) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,"User do not have write access");
        }
    }

    public DataServlet setSleepingpillCommunicator(SleepingpillCommunicator sleepingpillCommunicator) {
        this.sleepingpillCommunicator = sleepingpillCommunicator;
        return this;
    }
}
