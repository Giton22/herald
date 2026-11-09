#include "request.h"
#include "dictate.h"
#include "layout.h"

// PebbleLink.MAX_CHOICES and the byte limits it clips to.
#define MAX_CHOICES 6
#define PAD 6
#define HEADER_H LAYOUT_HEADER_H
#define STATUS_H 24
#define DETAIL_FONT FONT_KEY_GOTHIC_24

static Window *s_window;
static Layer *s_header_layer;
static TextLayer *s_status_layer;
static ScrollLayer *s_scroll_layer;
static TextLayer *s_detail_layer;

static GColor s_fill;
static GColor s_on_fill;
static char s_id[48];
static char s_title[64];
static char s_heading[64];
static char s_detail[704];
static char s_choices[MAX_CHOICES][64];
static int s_choice_count;
static Answering s_answering;
// An answer went and its outcome hasn't come.
static bool s_sending;
static char s_status[128];
// The choices open over the window, for the request s_id held when they opened.
static ActionMenu *s_menu;

static void prv_copy(char *to, size_t size, const char *from) {
  strncpy(to, from ? from : "", size - 1);
  to[size - 1] = '\0';
}

// The line under the header: how it's answered, or what happened to the answer.
static const char *prv_status_line(void) {
  if (s_sending) return "Sending...";
  if (s_status[0]) return s_status;
  return s_answering == ANSWER_ON_PHONE ? "Answer on your phone" : "Select to answer";
}

static void prv_header_update(Layer *layer, GContext *ctx) {
  layout_draw_header(ctx, layer_get_bounds(layer), s_heading, s_fill, s_on_fill, 0);
}

static void prv_redraw(void) {
  if (!s_window) return;
  layer_mark_dirty(s_header_layer);
  bool problem = s_status[0] && !s_sending;
  text_layer_set_text_color(s_status_layer, problem ? PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack)
                                                    : PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack));
  text_layer_set_text(s_status_layer, prv_status_line());
  // The chat it's in, then what it asks; a password's never says (it stays on the phone).
  static char body[sizeof(s_detail) + sizeof(s_title) + 12];
  snprintf(body, sizeof(body), "%s%s%s%s", s_title[0] ? "In " : "", s_title, s_title[0] && s_detail[0] ? ":\n" : "",
           s_detail[0] ? s_detail : (s_title[0] ? "" : "It's waiting on your phone."));
  text_layer_set_text(s_detail_layer, body);
  GRect bounds = layer_get_bounds(scroll_layer_get_layer(s_scroll_layer));
  int w = bounds.size.w - PAD * 2;
  int h = layout_fit(s_detail_layer, fonts_get_system_font(DETAIL_FONT), s_scroll_layer, PAD, 4, w);
  scroll_layer_set_content_size(s_scroll_layer, GSize(bounds.size.w, h + 16));
}

static void prv_send(int index, const char *text) {
  s_status[0] = '\0';
  s_sending = link_answer(s_id, index, text);
  if (!s_sending) prv_copy(s_status, sizeof(s_status), "Couldn't send that. Try again.");
  prv_redraw();
}

static void prv_dictated(const char *text) {
  if (s_window && !s_sending) prv_send(-1, text);
}

static void prv_choice(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  prv_send((int)action_menu_item_get_action_data(item), NULL);
}

static void prv_dictate(ActionMenu *menu, const ActionMenuItem *item, void *context) {
  dictate(prv_dictated);
}

static void prv_menu_closed(ActionMenu *menu, const ActionMenuItem *performed, void *context) {
  if (s_menu == menu) s_menu = NULL;
  action_menu_hierarchy_destroy(action_menu_get_root_level(menu), NULL, NULL);
}

// The choices and the dictation opened for the request on screen go: what's picked or said there would answer
// another one.
static void prv_forget_answering(void) {
  if (s_menu) action_menu_close(s_menu, false);
  dictate_forget(prv_dictated);
}

static void prv_select(ClickRecognizerRef recognizer, void *context) {
  if (s_answering == ANSWER_ON_PHONE || s_sending) return;
  ActionMenuLevel *root = action_menu_level_create(s_choice_count + 1);
  for (int i = 0; i < s_choice_count; i++) action_menu_level_add_action(root, s_choices[i], prv_choice, (void *)i);
  if (s_answering == ANSWER_CHOICES_OR_TEXT) action_menu_level_add_action(root, "Dictate", prv_dictate, NULL);
  ActionMenuConfig config = {
    .root_level = root,
    .colors = { .background = s_fill, .foreground = s_on_fill },
    .align = ActionMenuAlignCenter,
    .did_close = prv_menu_closed,
  };
  s_menu = action_menu_open(&config);
}

static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select);
}

static void prv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  window_set_background_color(window, GColorWhite);

  s_header_layer = layer_create(GRect(0, 0, bounds.size.w, HEADER_H));
  layer_set_update_proc(s_header_layer, prv_header_update);
  layer_add_child(root, s_header_layer);

  int inset = layout_inset(bounds, HEADER_H, STATUS_H, PAD);
  s_status_layer = text_layer_create(GRect(inset, HEADER_H, bounds.size.w - inset * 2, STATUS_H));
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_status_layer, LAYOUT_ALIGN);
  text_layer_set_overflow_mode(s_status_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(s_status_layer));

  int top = HEADER_H + STATUS_H;
  s_scroll_layer = scroll_layer_create(GRect(0, top, bounds.size.w, bounds.size.h - top));
  scroll_layer_set_shadow_hidden(s_scroll_layer, true);
  scroll_layer_set_click_config_onto_window(s_scroll_layer, window);
  scroll_layer_set_callbacks(s_scroll_layer, (ScrollLayerCallbacks) { .click_config_provider = prv_click_config });
  s_detail_layer = text_layer_create(GRect(PAD, 4, bounds.size.w - PAD * 2, 0));
  text_layer_set_font(s_detail_layer, fonts_get_system_font(DETAIL_FONT));
  scroll_layer_add_child(s_scroll_layer, text_layer_get_layer(s_detail_layer));
  layer_add_child(root, scroll_layer_get_layer(s_scroll_layer));
  prv_redraw();
}

static void prv_unload(Window *window) {
  text_layer_destroy(s_detail_layer);
  scroll_layer_destroy(s_scroll_layer);
  text_layer_destroy(s_status_layer);
  layer_destroy(s_header_layer);
  prv_forget_answering();
  window_destroy(s_window);
  s_window = NULL;
}

void request_show(const LinkRequest *request, GColor fill, GColor on_fill) {
  bool same = s_window && strcmp(s_id, request->request_id) == 0;
  s_fill = fill;
  s_on_fill = on_fill;
  prv_copy(s_id, sizeof(s_id), request->request_id);
  prv_copy(s_title, sizeof(s_title), request->title);
  prv_copy(s_heading, sizeof(s_heading), request->heading);
  prv_copy(s_detail, sizeof(s_detail), request->detail);
  s_answering = request->answering;
  // One choice per line.
  s_choice_count = 0;
  const char *at = request->choices;
  while (*at && s_choice_count < MAX_CHOICES) {
    const char *end = strchr(at, '\n');
    size_t len = end ? (size_t)(end - at) : strlen(at);
    if (len >= sizeof(s_choices[0])) len = sizeof(s_choices[0]) - 1;
    memcpy(s_choices[s_choice_count], at, len);
    s_choices[s_choice_count][len] = '\0';
    if (len > 0) s_choice_count++;
    if (!end) break;
    at = end + 1;
  }
  if (same) {
    prv_redraw();
    return;
  }
  prv_forget_answering();
  s_sending = false;
  s_status[0] = '\0';
  if (!s_window) {
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers) { .load = prv_load, .unload = prv_unload });
    window_stack_push(s_window, true);
  } else {
    scroll_layer_set_content_offset(s_scroll_layer, GPointZero, false);
    prv_redraw();
  }
  vibes_double_pulse();
}

void request_end(const char *request_id, const char *error) {
  if (!s_window || strcmp(s_id, request_id) != 0) return;
  if (error && error[0] && s_sending) {
    // The answer from here didn't go: say why, and it can be tried again.
    s_sending = false;
    prv_copy(s_status, sizeof(s_status), error);
    vibes_double_pulse();
    prv_redraw();
    return;
  }
  if (s_sending) vibes_short_pulse();
  window_stack_remove(s_window, true);
}

void request_on_send_failed(int type) {
  if (type != LINK_ANSWER || !s_window || !s_sending) return;
  // The answer never reached the phone, so no outcome will come: it can be tried again.
  s_sending = false;
  prv_copy(s_status, sizeof(s_status), "Couldn't reach Kyllene on your phone.");
  vibes_double_pulse();
  prv_redraw();
}
