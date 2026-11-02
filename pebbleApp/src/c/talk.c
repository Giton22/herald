#include "talk.h"
#include "chats.h"
#include "request.h"
#include "ring.h"
#include "dictate.h"
#include "layout.h"

// PebbleLink.MAX_REPLY_BYTES: the phone never sends more.
#define MAX_REPLY 6000
// PebbleLink.MAX_PROMPTS, each clipped to 60 bytes.
#define MAX_PROMPTS 6
// The phone answers a status request at once; none within this, Kyllene isn't there.
#define ANSWER_WITHIN_MS 5000
// While open, the phone's state is asked again this often (as the Wear watch does).
#define STATUS_EVERY_MS 30000

#define HEADER_H LAYOUT_HEADER_H
#define ACTIVITY_H 24
#define PAD 6
// A quick launch opens straight into dictation, once the window is up.
#define QUICK_DICTATE_MS 400
// How long the launcher says Kyllene's working, after it closed mid-turn.
#define GLANCE_WORKING_S (10 * 60)
// The launcher's line under Kyllene holds 150 bytes.
#define GLANCE_BYTES 140

// Asked with a press, from the hold-Select menu: these until the phone sends its own (Settings → Connected devices).
static char s_prompts[MAX_PROMPTS][64] = { "Summarize my day", "What's running?", "What needs my attention?" };
static int s_prompt_count = 3;
static char s_prompts_rev[16];

// Persisted; a prompt each from KEY_PROMPT.
enum {
  KEY_CHAT_ID = 1, KEY_CHAT_TITLE = 2, KEY_ACCENT_REV = 3, KEY_ACCENT_FILL = 4, KEY_ACCENT_ON_FILL = 5,
  KEY_PROMPTS_REV = 6, KEY_PROMPT_COUNT = 7, KEY_PROMPT = 10,
};

typedef enum {
  PHONE_UNKNOWN,
  // No phone is connected to the watch.
  PHONE_NOT_CONNECTED,
  // A phone is, but Kyllene there didn't answer.
  PHONE_NO_HERALD,
  PHONE_SIGNED_OUT,
  // Kyllene's Pebble switch is off.
  PHONE_OFF,
  // Signed in, and Kyllene gave up reaching the gateway.
  PHONE_UNREACHABLE,
  PHONE_RECONNECTING,
  PHONE_READY,
} PhoneState;

static Window *s_window;
static Layer *s_header_layer;
static TextLayer *s_activity_layer;
static ScrollLayer *s_scroll_layer;
static TextLayer *s_said_layer;
static TextLayer *s_reply_layer;
static AppTimer *s_answer_timer;
static AppTimer *s_status_timer;

static PhoneState s_phone = PHONE_UNKNOWN;
static char s_chat_id[64];
static char s_chat_title[64];
static char s_accent_rev[48];
static GColor s_fill;
static GColor s_on_fill;

// The turn on screen: the ask it answers, what was said (when said here), the reply so far.
static bool s_has_turn;
static char s_ask_id[24];
static char s_said[300];
static char s_reply[MAX_REPLY + 1];
static int s_reply_len;
static Phase s_phase;
// A chat's last exchange was asked for and hasn't come.
static bool s_loading_chat;
static char s_activity[128];
static char s_error[128];
static int s_ask_count;

static void prv_copy(char *to, size_t size, const char *from) {
  strncpy(to, from ? from : "", size - 1);
  to[size - 1] = '\0';
}

static bool prv_running(void) {
  return s_has_turn && s_phase <= PHASE_WAITING_ON_PHONE;
}

// --- What the screen says

static const char *prv_phone_problem_short(void) {
  switch (s_phone) {
    case PHONE_NOT_CONNECTED: return "Phone not connected";
    case PHONE_NO_HERALD: return "No answer from Kyllene";
    case PHONE_SIGNED_OUT: return "Sign in on your phone";
    case PHONE_OFF: return "Off in Kyllene";
    case PHONE_UNREACHABLE: return "Gateway out of reach";
    default: return NULL;
  }
}

// The same words as the Wear OS watch.
static const char *prv_phone_problem(void) {
  switch (s_phone) {
    case PHONE_NOT_CONNECTED: return "Your phone isn't connected.";
    case PHONE_NO_HERALD: return "Kyllene on your phone didn't answer. Open it there, or update it.";
    case PHONE_SIGNED_OUT: return "Sign in to Kyllene on your phone first.";
    case PHONE_OFF: return "Kyllene on the Pebble is off. Turn it on in Kyllene's settings on your phone.";
    case PHONE_UNREACHABLE: return "Kyllene can't reach your gateway. Check it on your phone.";
    default: return NULL;
  }
}

static const char *prv_phase_line(void) {
  switch (s_phase) {
    case PHASE_TRANSCRIBING: return "Writing it down...";
    case PHASE_SENDING: return "Sending...";
    case PHASE_WORKING: return "Thinking...";
    case PHASE_WAITING_ON_PHONE: return "Waiting for you on your phone";
    default: return "";
  }
}

// The line under the header: the turn's state, else the phone's, else what Select does.
static const char *prv_activity_line(void) {
  if (s_has_turn && s_phase == PHASE_FAILED) {
    // With no reply, the body says why at length.
    if (!s_reply[0]) return "It didn't go";
    return s_error[0] ? s_error : "Something went wrong.";
  }
  if (prv_running()) return s_activity[0] ? s_activity : prv_phase_line();
  if (s_loading_chat) return "Opening the chat...";
  const char *problem = prv_phone_problem_short();
  if (problem) return problem;
  return s_has_turn ? "Select to reply" : "Select to talk";
}

#ifndef PBL_ROUND
static GColor prv_dot(void) {
  switch (s_phone) {
    case PHONE_READY: return PBL_IF_COLOR_ELSE(GColorIslamicGreen, GColorWhite);
    case PHONE_RECONNECTING: return PBL_IF_COLOR_ELSE(GColorChromeYellow, GColorWhite);
    case PHONE_UNKNOWN: return GColorClear;
    default: return PBL_IF_COLOR_ELSE(GColorRed, GColorWhite);
  }
}
#endif

static void prv_header_update(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  layout_draw_header(ctx, bounds, s_chat_title[0] ? s_chat_title : "New chat", s_fill, s_on_fill, 14);
#ifndef PBL_ROUND
  // The phone's state as a dot; a round header has no room for it, and the line below says it anyway.
  GColor dot = prv_dot();
  if (!gcolor_equal(dot, GColorClear)) {
    graphics_context_set_fill_color(ctx, dot);
    graphics_fill_circle(ctx, GPoint(bounds.size.w - PAD - 4, HEADER_H / 2), 4);
    graphics_context_set_stroke_color(ctx, s_on_fill);
    graphics_draw_circle(ctx, GPoint(bounds.size.w - PAD - 4, HEADER_H / 2), 4);
  }
#endif
}

// Sizes the said and reply text to what they hold, the reply below what was said.
static void prv_layout(void) {
  GRect bounds = layer_get_bounds(scroll_layer_get_layer(s_scroll_layer));
  int w = bounds.size.w - PAD * 2;
  int y = 4;
  int said = layout_fit(s_said_layer, s_scroll_layer, PAD, y, w);
  if (said) y += said + 2;
  int h = layout_fit(s_reply_layer, s_scroll_layer, PAD, y, w);
  scroll_layer_set_content_size(s_scroll_layer, GSize(bounds.size.w, y + h + 12));
}

static void prv_redraw(void) {
  if (!s_window) return;
  layer_mark_dirty(s_header_layer);
  bool failed = s_has_turn && s_phase == PHASE_FAILED;
  text_layer_set_text_color(s_activity_layer, failed ? PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack)
                                                     : PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack));
  text_layer_set_text(s_activity_layer, prv_activity_line());
  text_layer_set_text(s_said_layer, s_said);
  if (s_has_turn && !s_reply[0] && s_phase == PHASE_FAILED) {
    // Nothing came back: the whole of why, where there's room for it.
    text_layer_set_text(s_reply_layer, s_error[0] ? s_error : "Something went wrong.");
  } else if (s_has_turn) {
    text_layer_set_text(s_reply_layer, s_reply);
  } else if (s_loading_chat) {
    text_layer_set_text(s_reply_layer, "");
  } else {
    // Nothing asked yet: what's wrong, or how to start.
    const char *problem = prv_phone_problem();
    text_layer_set_text(s_reply_layer, problem ? problem : "Press Select and speak to Hermes.\n\nHold Select for prompts and your chats.");
  }
  prv_layout();
}

// --- The phone

static void prv_answer_timeout(void *data) {
  s_answer_timer = NULL;
  if (s_phone != PHONE_NOT_CONNECTED) s_phone = PHONE_NO_HERALD;
  prv_redraw();
}

static void prv_ask_status(void) {
  if (!link_phone_connected()) {
    s_phone = PHONE_NOT_CONNECTED;
    prv_redraw();
    return;
  }
  if (!link_status_request(s_accent_rev, s_prompts_rev)) return;
  if (!s_answer_timer) s_answer_timer = app_timer_register(ANSWER_WITHIN_MS, prv_answer_timeout, NULL);
}

static void prv_status_tick(void *data) {
  s_status_timer = app_timer_register(STATUS_EVERY_MS, prv_status_tick, NULL);
  prv_ask_status();
}

void talk_on_status(const LinkStatus *status) {
  if (s_answer_timer) {
    app_timer_cancel(s_answer_timer);
    s_answer_timer = NULL;
  }
  if (status->flags & LINK_FLAG_OFF) s_phone = PHONE_OFF;
  else if (!(status->flags & LINK_FLAG_SIGNED_IN)) s_phone = PHONE_SIGNED_OUT;
  else if (status->flags & LINK_FLAG_FAILED) s_phone = PHONE_UNREACHABLE;
  else if (status->flags & LINK_FLAG_CONNECTED) s_phone = PHONE_READY;
  else s_phone = PHONE_RECONNECTING;
  // Opened in a chat with no turn to show: its last exchange, once the phone's there to send it.
  static bool s_asked_history;
  if (!s_asked_history && !s_has_turn && s_chat_id[0] && s_phone == PHONE_READY) {
    s_asked_history = true;
    s_loading_chat = link_history_request(s_chat_id);
  }
  if (s_phone == PHONE_OFF && s_has_turn && s_phase == PHASE_SENDING) {
    // What was just said went nowhere.
    s_phase = PHASE_FAILED;
    prv_copy(s_error, sizeof(s_error), prv_phone_problem());
  }
  if (status->has_accent) {
    // The phone's dark accent: its fill for the header, its on-fill for the header's text.
    s_fill = PBL_IF_COLOR_ELSE(GColorFromHEX(status->fill), GColorBlack);
    s_on_fill = PBL_IF_COLOR_ELSE(GColorFromHEX(status->on_fill), GColorWhite);
    strncpy(s_accent_rev, status->accent_rev, sizeof(s_accent_rev) - 1);
    persist_write_string(KEY_ACCENT_REV, s_accent_rev);
    persist_write_int(KEY_ACCENT_FILL, (int32_t)status->fill);
    persist_write_int(KEY_ACCENT_ON_FILL, (int32_t)status->on_fill);
  }
  prv_redraw();
}

static void prv_phone_connection(bool connected) {
  if (connected) {
    prv_ask_status();
  } else {
    s_phone = PHONE_NOT_CONNECTED;
    prv_redraw();
  }
}

// --- The turn

static void prv_ask(const char *text) {
  snprintf(s_ask_id, sizeof(s_ask_id), "p%lx%d", (unsigned long)time(NULL), ++s_ask_count);
  s_has_turn = true;
  prv_copy(s_said, sizeof(s_said), text);
  s_reply[0] = '\0';
  s_reply_len = 0;
  s_activity[0] = '\0';
  s_error[0] = '\0';
  s_phase = PHASE_SENDING;
  if (!link_ask(s_ask_id, text, s_chat_id)) {
    s_phase = PHASE_FAILED;
    prv_copy(s_error, sizeof(s_error), "Couldn't send that. Try again.");
  }
  scroll_layer_set_content_offset(s_scroll_layer, GPointZero, false);
  prv_redraw();
}

void talk_on_send_failed(int type) {
  if (type == LINK_ASK && s_has_turn && s_phase == PHASE_SENDING) {
    s_phase = PHASE_FAILED;
    prv_copy(s_error, sizeof(s_error), "Couldn't reach Kyllene on your phone.");
    vibes_double_pulse();
    prv_redraw();
  } else if (type == LINK_STATUS_REQ) {
    // Not even delivered: the answer won't come.
    if (s_answer_timer) app_timer_cancel(s_answer_timer);
    prv_answer_timeout(NULL);
  }
}

void talk_on_reply(const LinkReply *reply) {
  s_loading_chat = false;
  if (!s_has_turn || strcmp(reply->ask_id, s_ask_id) != 0) {
    // Another ask's turn: one from before this app opened, which the phone sends again on opening.
    s_has_turn = true;
    prv_copy(s_ask_id, sizeof(s_ask_id), reply->ask_id);
    s_said[0] = '\0';
    s_reply[0] = '\0';
    s_reply_len = 0;
  }
  if (reply->text_offset > s_reply_len) {
    // Told to add past what this has: it lost the start. The phone sends it all again.
    link_resync(s_ask_id);
    return;
  }
  // Placed at its offset: the same piece sent twice changes nothing, and 0 replaces the text.
  int len = strlen(reply->text);
  if (reply->text_offset + len > MAX_REPLY) len = MAX_REPLY - reply->text_offset;
  memcpy(s_reply + reply->text_offset, reply->text, len);
  s_reply_len = reply->text_offset + len;
  s_reply[s_reply_len] = '\0';

  Phase was = s_phase;
  s_phase = reply->phase;
  prv_copy(s_activity, sizeof(s_activity), reply->activity);
  prv_copy(s_error, sizeof(s_error), reply->error);
  if (reply->said) prv_copy(s_said, sizeof(s_said), reply->said);
  if (reply->chat_id && reply->chat_id[0] && strcmp(reply->chat_id, s_chat_id) != 0) {
    prv_copy(s_chat_id, sizeof(s_chat_id), reply->chat_id);
    persist_write_string(KEY_CHAT_ID, s_chat_id);
  }
  if (reply->title && reply->title[0] && strcmp(reply->title, s_chat_title) != 0) {
    prv_copy(s_chat_title, sizeof(s_chat_title), reply->title);
    persist_write_string(KEY_CHAT_TITLE, s_chat_title);
  }
  // A turn only answers once it has a phone to answer it.
  if (s_phone == PHONE_NO_HERALD || s_phone == PHONE_UNKNOWN) s_phone = PHONE_READY;
  if (s_phase != was) {
    if (s_phase == PHASE_DONE) vibes_short_pulse();
    else if (s_phase == PHASE_FAILED || s_phase == PHASE_WAITING_ON_PHONE) vibes_double_pulse();
  }
  prv_redraw();
}

void talk_on_request(const LinkRequest *request) {
  request_show(request, s_fill, s_on_fill);
}

void talk_on_prompts(const char *revision, const char *lines) {
  // One per line; none at all leaves the menu without Prompts.
  s_prompt_count = 0;
  const char *at = lines;
  while (*at && s_prompt_count < MAX_PROMPTS) {
    const char *end = strchr(at, '\n');
    size_t len = end ? (size_t)(end - at) : strlen(at);
    if (len >= sizeof(s_prompts[0])) len = sizeof(s_prompts[0]) - 1;
    if (len > 0) {
      memcpy(s_prompts[s_prompt_count], at, len);
      s_prompts[s_prompt_count][len] = '\0';
      persist_write_string(KEY_PROMPT + s_prompt_count, s_prompts[s_prompt_count]);
      s_prompt_count++;
    }
    if (!end) break;
    at = end + 1;
  }
  persist_write_int(KEY_PROMPT_COUNT, s_prompt_count);
  prv_copy(s_prompts_rev, sizeof(s_prompts_rev), revision);
  persist_write_string(KEY_PROMPTS_REV, s_prompts_rev);
}

void talk_on_ring(const LinkRing *ring) {
  ring_show(ring, s_fill, s_on_fill);
}

// Talks in [chat_id] from the next ask on; an empty id is a new chat.
static void prv_use_chat(const char *chat_id, const char *title) {
  prv_copy(s_chat_id, sizeof(s_chat_id), chat_id);
  prv_copy(s_chat_title, sizeof(s_chat_title), title);
  if (s_chat_id[0]) {
    persist_write_string(KEY_CHAT_ID, s_chat_id);
    persist_write_string(KEY_CHAT_TITLE, s_chat_title);
  } else {
    persist_delete(KEY_CHAT_ID);
    persist_delete(KEY_CHAT_TITLE);
  }
  s_has_turn = false;
  s_said[0] = '\0';
  s_ask_id[0] = '\0';
  // An old chat opens on its last exchange.
  s_loading_chat = s_chat_id[0] && link_history_request(s_chat_id);
  scroll_layer_set_content_offset(s_scroll_layer, GPointZero, false);
  prv_redraw();
}

// --- Buttons

static void prv_dictated(const char *text) {
  prv_ask(text);
}

static void prv_select(ClickRecognizerRef recognizer, void *context) {
  if (!link_phone_connected()) {
    s_phone = PHONE_NOT_CONNECTED;
    vibes_short_pulse();
    prv_redraw();
    return;
  }
  dictate(prv_dictated);
}

static void prv_menu_new_chat(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  prv_use_chat("", "");
}

static void prv_menu_chats(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  chats_open(s_chat_id, s_fill, s_on_fill, prv_use_chat);
}

static void prv_menu_stop(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  if (!prv_running() || !link_stop(s_ask_id)) return;
  // The phone stops telling once it stops the turn: what came so far stays as the reply.
  s_phase = PHASE_DONE;
  s_activity[0] = '\0';
  prv_redraw();
}

static void prv_menu_prompt(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  if (!link_phone_connected()) {
    s_phone = PHONE_NOT_CONNECTED;
    prv_redraw();
    return;
  }
  prv_ask(action_menu_item_get_action_data(item));
}

static void prv_menu_check_phone(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  s_phone = PHONE_UNKNOWN;
  prv_ask_status();
  prv_redraw();
}

static void prv_menu_closed(ActionMenu *menu, const ActionMenuItem *performed, void *context) {
  action_menu_hierarchy_destroy(action_menu_get_root_level(menu), NULL, NULL);
}

static void prv_menu(ClickRecognizerRef recognizer, void *context) {
  ActionMenuLevel *root = action_menu_level_create(5);
  if (prv_running()) action_menu_level_add_action(root, "Stop", prv_menu_stop, NULL);
  if (!prv_running() && s_prompt_count > 0) {
    ActionMenuLevel *prompts = action_menu_level_create(s_prompt_count);
    for (int i = 0; i < s_prompt_count; i++) {
      action_menu_level_add_action(prompts, s_prompts[i], prv_menu_prompt, s_prompts[i]);
    }
    action_menu_level_add_child(root, prompts, "Prompts");
  }
  action_menu_level_add_action(root, "New chat", prv_menu_new_chat, NULL);
  action_menu_level_add_action(root, "Chats", prv_menu_chats, NULL);
  action_menu_level_add_action(root, "Check phone", prv_menu_check_phone, NULL);
  ActionMenuConfig config = {
    .root_level = root,
    .colors = { .background = s_fill, .foreground = s_on_fill },
    .align = ActionMenuAlignCenter,
    .did_close = prv_menu_closed,
  };
  action_menu_open(&config);
}

// Up and Down stay the scroll layer's: they scroll the reply.
static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select);
  window_long_click_subscribe(BUTTON_ID_SELECT, 0, prv_menu, NULL);
}

// --- Touch: a tap talks, as Select does; a drag scrolls the reply.

#ifdef _PBL_API_EXISTS_tap_recognizer_create
static int16_t s_scroll_from;

static void prv_tap(const Recognizer *recognizer, RecognizerEvent event) {
  if (event == RecognizerEvent_Completed) prv_select(NULL, NULL);
}

static void prv_pan(const Recognizer *recognizer, RecognizerEvent event) {
  if (event == RecognizerEvent_Started) {
    s_scroll_from = scroll_layer_get_content_offset(s_scroll_layer).y;
  } else if (event == RecognizerEvent_Updated) {
    GPoint moved = pan_recognizer_get_delta_since_start(recognizer);
    scroll_layer_set_content_offset(s_scroll_layer, GPoint(0, s_scroll_from + moved.y), false);
  }
}
#endif

// --- The window

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  window_set_background_color(window, GColorWhite);

  s_header_layer = layer_create(GRect(0, 0, bounds.size.w, HEADER_H));
  layer_set_update_proc(s_header_layer, prv_header_update);
  layer_add_child(root, s_header_layer);

  int inset = layout_inset(bounds, HEADER_H, ACTIVITY_H, PAD);
  s_activity_layer = text_layer_create(GRect(inset, HEADER_H, bounds.size.w - inset * 2, ACTIVITY_H));
  text_layer_set_font(s_activity_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_activity_layer, LAYOUT_ALIGN);
  text_layer_set_overflow_mode(s_activity_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(s_activity_layer));

  int top = HEADER_H + ACTIVITY_H;
  s_scroll_layer = scroll_layer_create(GRect(0, top, bounds.size.w, bounds.size.h - top));
  scroll_layer_set_shadow_hidden(s_scroll_layer, true);
  scroll_layer_set_click_config_onto_window(s_scroll_layer, window);
  scroll_layer_set_callbacks(s_scroll_layer, (ScrollLayerCallbacks) { .click_config_provider = prv_click_config });

  s_said_layer = text_layer_create(GRect(PAD, 4, bounds.size.w - PAD * 2, 0));
  text_layer_set_font(s_said_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_color(s_said_layer, PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack));
  scroll_layer_add_child(s_scroll_layer, text_layer_get_layer(s_said_layer));

  s_reply_layer = text_layer_create(GRect(PAD, 4, bounds.size.w - PAD * 2, 0));
  text_layer_set_font(s_reply_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24));
  scroll_layer_add_child(s_scroll_layer, text_layer_get_layer(s_reply_layer));
  layer_add_child(root, scroll_layer_get_layer(s_scroll_layer));

#ifdef _PBL_API_EXISTS_tap_recognizer_create
  // Its own taps and drags instead of the system's button emulation; the window destroys them as it unloads.
  window_set_touch_bridge_disabled(window, true);
  window_attach_recognizer(window, tap_recognizer_create(prv_tap, NULL));
  window_attach_recognizer(window, pan_recognizer_create(prv_pan, NULL, PanAxis_Vertical));
#endif

  prv_redraw();
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_reply_layer);
  text_layer_destroy(s_said_layer);
  scroll_layer_destroy(s_scroll_layer);
  text_layer_destroy(s_activity_layer);
  layer_destroy(s_header_layer);
}

static void prv_first_status(void *data) {
  prv_status_tick(NULL);
}

static void prv_quick_dictate(void *data) {
  prv_select(NULL, NULL);
}

// --- The launcher's glance (the Wear tile's last reply): the reply's first line, or that it's still working.

#ifdef _PBL_API_EXISTS_app_glance_reload
static void prv_add_glance(AppGlanceReloadSession *session, const char *line, time_t until) {
  app_glance_add_slice(session, (AppGlanceSlice) {
    .layout = { .icon = APP_GLANCE_SLICE_DEFAULT_ICON, .subtitle_template_string = line },
    .expiration_time = until,
  });
}

static void prv_glance(AppGlanceReloadSession *session, size_t limit, void *context) {
  if (limit == 0) return;
  if (prv_running()) {
    // Ended meanwhile, its reply comes as a notification: past this, the glance asks again.
    prv_add_glance(session, "Working on it...", time(NULL) + GLANCE_WORKING_S);
    if (limit > 1) prv_add_glance(session, "Ask Hermes anything", APP_GLANCE_SLICE_NO_EXPIRATION);
    return;
  }
  static char line[GLANCE_BYTES + 1];
  const char *from = s_reply;
  while (*from == '\n' || *from == ' ') from++;
  int n = 0;
  while (from[n] && from[n] != '\n' && n < GLANCE_BYTES) n++;
  // Never half a character.
  if (from[n] && from[n] != '\n') while (n > 0 && (from[n] & 0xC0) == 0x80) n--;
  for (int i = 0; i < n; i++) {
    // Braces start a template's fields.
    line[i] = from[i] == '{' ? '(' : from[i] == '}' ? ')' : from[i];
  }
  line[n] = '\0';
  bool has_reply = s_has_turn && s_phase == PHASE_DONE && n > 0;
  prv_add_glance(session, has_reply ? line : "Ask Hermes anything", APP_GLANCE_SLICE_NO_EXPIRATION);
}
#endif

void talk_init(void) {
  // Kyllene blue on white until the phone tells its accent.
  s_fill = PBL_IF_COLOR_ELSE(GColorFromHEX(0x2F6BF5), GColorBlack);
  s_on_fill = GColorWhite;
  if (persist_exists(KEY_CHAT_ID)) persist_read_string(KEY_CHAT_ID, s_chat_id, sizeof(s_chat_id));
  if (persist_exists(KEY_CHAT_TITLE)) persist_read_string(KEY_CHAT_TITLE, s_chat_title, sizeof(s_chat_title));
  if (persist_exists(KEY_ACCENT_REV)) {
    persist_read_string(KEY_ACCENT_REV, s_accent_rev, sizeof(s_accent_rev));
    s_fill = PBL_IF_COLOR_ELSE(GColorFromHEX(persist_read_int(KEY_ACCENT_FILL)), GColorBlack);
    s_on_fill = PBL_IF_COLOR_ELSE(GColorFromHEX(persist_read_int(KEY_ACCENT_ON_FILL)), GColorWhite);
  }
  if (persist_exists(KEY_PROMPTS_REV)) {
    persist_read_string(KEY_PROMPTS_REV, s_prompts_rev, sizeof(s_prompts_rev));
    s_prompt_count = persist_read_int(KEY_PROMPT_COUNT);
    if (s_prompt_count > MAX_PROMPTS) s_prompt_count = MAX_PROMPTS;
    for (int i = 0; i < s_prompt_count; i++) persist_read_string(KEY_PROMPT + i, s_prompts[i], sizeof(s_prompts[i]));
  }
  connection_service_subscribe((ConnectionHandlers) { .pebble_app_connection_handler = prv_phone_connection });

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
  // A moment after opening, once the link is up.
  app_timer_register(300, prv_first_status, NULL);
  if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH) app_timer_register(QUICK_DICTATE_MS, prv_quick_dictate, NULL);
}

void talk_deinit(void) {
#ifdef _PBL_API_EXISTS_app_glance_reload
  app_glance_reload(prv_glance, NULL);
#endif
  connection_service_unsubscribe();
  dictate_deinit();
  window_destroy(s_window);
  s_window = NULL;
}
