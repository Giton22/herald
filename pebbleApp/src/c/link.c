#include "link.h"

// PebbleLink.WATCH_INBOX_BYTES: every message the phone sends fits.
#define INBOX_SIZE 2048
// The longest this sends: an ask with its dictation, chat and scope.
#define OUTBOX_SIZE 1024

enum { TYPE_REPLY = 64, TYPE_CHAT_ITEM = 65, TYPE_STATUS = 66, TYPE_RING = 67, TYPE_RING_END = 68, TYPE_REQUEST = 69, TYPE_REQUEST_END = 70, TYPE_PROMPTS = 71 };

static LinkHandlers s_handlers;

// A number the phone sent, whatever size it was written in.
static int32_t prv_int(const Tuple *t) {
  if (!t) return 0;
  switch (t->length) {
    case 1: return t->type == TUPLE_INT ? t->value->int8 : t->value->uint8;
    case 2: return t->type == TUPLE_INT ? t->value->int16 : t->value->uint16;
    default: return t->type == TUPLE_INT ? t->value->int32 : (int32_t)t->value->uint32;
  }
}

// Text the phone sent, or NULL when it sent none (or something else under the key).
static const char *prv_text(DictionaryIterator *in, uint32_t key) {
  const Tuple *t = dict_find(in, key);
  return (t && t->type == TUPLE_CSTRING) ? t->value->cstring : NULL;
}

static void prv_on_reply(DictionaryIterator *in) {
  const char *text = prv_text(in, MESSAGE_KEY_TEXT);
  LinkReply reply = {
    .ask_id = prv_text(in, MESSAGE_KEY_ASK_ID),
    .seq = prv_int(dict_find(in, MESSAGE_KEY_SEQ)),
    .phase = (Phase)prv_int(dict_find(in, MESSAGE_KEY_PHASE)),
    .chat_id = prv_text(in, MESSAGE_KEY_CHAT_ID),
    .title = prv_text(in, MESSAGE_KEY_TITLE),
    .activity = prv_text(in, MESSAGE_KEY_ACTIVITY),
    .error = prv_text(in, MESSAGE_KEY_ERROR),
    .said = prv_text(in, MESSAGE_KEY_SAID),
    .scope = prv_text(in, MESSAGE_KEY_SCOPE),
    .text = text ? text : "",
    .text_offset = prv_int(dict_find(in, MESSAGE_KEY_TEXT_OFFSET)),
    .text_total = prv_int(dict_find(in, MESSAGE_KEY_TEXT_TOTAL)),
  };
  if (!reply.ask_id || reply.phase > PHASE_FAILED) return;
  if (s_handlers.on_reply) s_handlers.on_reply(&reply);
}

static void prv_on_status(DictionaryIterator *in) {
  LinkStatus status = { .flags = prv_int(dict_find(in, MESSAGE_KEY_FLAGS)) };
  status.accent_rev = prv_text(in, MESSAGE_KEY_ACCENT_REV);
  status.has_accent = status.accent_rev != NULL;
  status.fill = prv_int(dict_find(in, MESSAGE_KEY_ACCENT_FILL));
  status.text = prv_int(dict_find(in, MESSAGE_KEY_ACCENT_TEXT));
  status.on_fill = prv_int(dict_find(in, MESSAGE_KEY_ACCENT_ON_FILL));
  status.dim = prv_int(dict_find(in, MESSAGE_KEY_ACCENT_DIM));
  if (s_handlers.on_status) s_handlers.on_status(&status);
}

static void prv_on_chat_item(DictionaryIterator *in) {
  LinkChatItem item = {
    .index = prv_int(dict_find(in, MESSAGE_KEY_INDEX)),
    .count = prv_int(dict_find(in, MESSAGE_KEY_COUNT)),
    .chat_id = prv_text(in, MESSAGE_KEY_CHAT_ID),
    .title = prv_text(in, MESSAGE_KEY_TITLE),
    .ago = prv_text(in, MESSAGE_KEY_AGO),
    .scope = prv_text(in, MESSAGE_KEY_SCOPE),
    .error = prv_text(in, MESSAGE_KEY_ERROR),
  };
  if (s_handlers.on_chat_item) s_handlers.on_chat_item(&item);
}

static void prv_on_request(DictionaryIterator *in) {
  const char *choices = prv_text(in, MESSAGE_KEY_CHOICES);
  const char *detail = prv_text(in, MESSAGE_KEY_DETAIL);
  const char *heading = prv_text(in, MESSAGE_KEY_ACTIVITY);
  LinkRequest request = {
    .request_id = prv_text(in, MESSAGE_KEY_REQUEST_ID),
    .chat_id = prv_text(in, MESSAGE_KEY_CHAT_ID),
    .title = prv_text(in, MESSAGE_KEY_TITLE),
    .approval = prv_int(dict_find(in, MESSAGE_KEY_APPROVAL)) == 1,
    .heading = heading ? heading : "",
    .detail = detail ? detail : "",
    .choices = choices ? choices : "",
    .answering = (Answering)prv_int(dict_find(in, MESSAGE_KEY_ANSWERING)),
  };
  if (!request.request_id || request.answering > ANSWER_CHOICES_OR_TEXT) return;
  if (s_handlers.on_request) s_handlers.on_request(&request);
}

static void prv_on_ring(DictionaryIterator *in) {
  const char *chat_id = prv_text(in, MESSAGE_KEY_CHAT_ID);
  const char *title = prv_text(in, MESSAGE_KEY_TITLE);
  LinkRing ring = {
    .request_id = prv_text(in, MESSAGE_KEY_REQUEST_ID),
    .chat_id = chat_id ? chat_id : "",
    .title = title ? title : "Hermes",
    .approval = prv_int(dict_find(in, MESSAGE_KEY_APPROVAL)) == 1,
    .agent = prv_int(dict_find(in, MESSAGE_KEY_AGENT)) == 1,
    .answering = (Answering)prv_int(dict_find(in, MESSAGE_KEY_ANSWERING)),
  };
  if (!ring.request_id || ring.answering > ANSWER_CHOICES_OR_TEXT) return;
  if (s_handlers.on_ring) s_handlers.on_ring(&ring);
}

static void prv_inbox_received(DictionaryIterator *in, void *context) {
  switch (prv_int(dict_find(in, MESSAGE_KEY_TYPE))) {
    case TYPE_REPLY: prv_on_reply(in); break;
    case TYPE_STATUS: prv_on_status(in); break;
    case TYPE_CHAT_ITEM: prv_on_chat_item(in); break;
    case TYPE_REQUEST: prv_on_request(in); break;
    case TYPE_RING: prv_on_ring(in); break;
    case TYPE_PROMPTS: {
      const char *rev = prv_text(in, MESSAGE_KEY_PROMPTS_REV);
      const char *lines = prv_text(in, MESSAGE_KEY_PROMPTS);
      if (rev && s_handlers.on_prompts) s_handlers.on_prompts(rev, lines ? lines : "");
      break;
    }
    case TYPE_RING_END: {
      const char *id = prv_text(in, MESSAGE_KEY_REQUEST_ID);
      if (id && s_handlers.on_ring_end) s_handlers.on_ring_end(id);
      break;
    }
    case TYPE_REQUEST_END: {
      const char *id = prv_text(in, MESSAGE_KEY_REQUEST_ID);
      if (id && s_handlers.on_request_end) s_handlers.on_request_end(id, prv_text(in, MESSAGE_KEY_ERROR));
      break;
    }
    default: break;
  }
}

static void prv_inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped a message: %d", (int)reason);
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  const Tuple *type = dict_find(iter, MESSAGE_KEY_TYPE);
  APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox failed: %d", (int)reason);
  if (s_handlers.on_send_failed) s_handlers.on_send_failed(prv_int(type));
}

static void prv_outbox_sent(DictionaryIterator *iter, void *context) {
  if (s_handlers.on_sent) s_handlers.on_sent(prv_int(dict_find(iter, MESSAGE_KEY_TYPE)));
}

void link_open(LinkHandlers handlers) {
  s_handlers = handlers;
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_inbox_dropped(prv_inbox_dropped);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_register_outbox_sent(prv_outbox_sent);
  app_message_open(INBOX_SIZE, OUTBOX_SIZE);
}

static bool prv_begin(DictionaryIterator **out, int type) {
  AppMessageResult result = app_message_outbox_begin(out);
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox busy (%d), type %d not sent", (int)result, type);
    return false;
  }
  dict_write_int32(*out, MESSAGE_KEY_TYPE, type);
  return true;
}

static bool prv_send(void) {
  return app_message_outbox_send() == APP_MSG_OK;
}

bool link_ask(const char *ask_id, const char *text, const char *chat_id, const char *scope) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_ASK)) return false;
  dict_write_cstring(out, MESSAGE_KEY_ASK_ID, ask_id);
  dict_write_cstring(out, MESSAGE_KEY_TEXT, text);
  if (chat_id && chat_id[0]) {
    dict_write_cstring(out, MESSAGE_KEY_CHAT_ID, chat_id);
    if (scope && scope[0]) dict_write_cstring(out, MESSAGE_KEY_SCOPE, scope);
  }
  return prv_send();
}

bool link_stop(const char *ask_id) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_STOP)) return false;
  dict_write_cstring(out, MESSAGE_KEY_ASK_ID, ask_id);
  return prv_send();
}

bool link_status_request(const char *accent_rev, const char *prompts_rev) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_STATUS_REQ)) return false;
  if (accent_rev && accent_rev[0]) dict_write_cstring(out, MESSAGE_KEY_ACCENT_REV, accent_rev);
  if (prompts_rev && prompts_rev[0]) dict_write_cstring(out, MESSAGE_KEY_PROMPTS_REV, prompts_rev);
  return prv_send();
}

bool link_chats_request(void) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_CHATS_REQ)) return false;
  return prv_send();
}

bool link_history_request(const char *chat_id, const char *scope) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_HISTORY_REQ)) return false;
  dict_write_cstring(out, MESSAGE_KEY_CHAT_ID, chat_id);
  if (scope && scope[0]) dict_write_cstring(out, MESSAGE_KEY_SCOPE, scope);
  return prv_send();
}

bool link_resync(const char *ask_id) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_RESYNC)) return false;
  dict_write_cstring(out, MESSAGE_KEY_ASK_ID, ask_id);
  return prv_send();
}

bool link_answer(const char *request_id, int index, const char *text) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_ANSWER)) return false;
  dict_write_cstring(out, MESSAGE_KEY_REQUEST_ID, request_id);
  dict_write_int32(out, MESSAGE_KEY_INDEX, index);
  if (text) dict_write_cstring(out, MESSAGE_KEY_TEXT, text);
  return prv_send();
}

bool link_ring_on(const char *request_id) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_RING_ON)) return false;
  dict_write_cstring(out, MESSAGE_KEY_REQUEST_ID, request_id);
  return prv_send();
}

bool link_ring_end(const char *request_id, bool answered) {
  DictionaryIterator *out;
  if (!prv_begin(&out, LINK_RING_END)) return false;
  dict_write_cstring(out, MESSAGE_KEY_REQUEST_ID, request_id);
  dict_write_int32(out, MESSAGE_KEY_ANSWERED, answered ? 1 : 0);
  return prv_send();
}

bool link_phone_connected(void) {
  return connection_service_peek_pebble_app_connection();
}
