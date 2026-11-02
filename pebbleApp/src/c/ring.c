#include "ring.h"
#include "layout.h"

// ChatNotifications.RING_MS: the phone rings no longer.
#define RING_MS 45000
#define BUZZ_EVERY_MS 3000
// The outbox is often busy as the app opens (the talk screen asks for the phone's status then): sent again.
#define RETRY_MS 400
#define RETRIES 10
#define VOLUME 70
#define PAD 6
#define HEADER_H LAYOUT_HEADER_H
// Room the button labels take at the right.
#define LABELS_W 66
// Narrower than this (a 144-wide screen), the ring stacks its lines.
#define SMALL_W 180

static Window *s_window;
static Layer *s_header_layer;
static TextLayer *s_title_layer;
static TextLayer *s_why_layer;
static TextLayer *s_answer_layer;
static TextLayer *s_decline_layer;

static GColor s_fill;
static GColor s_on_fill;
static char s_id[48];
static char s_title[64];
static char s_why[48];
static Answering s_answering;
// Answer was pressed, and it's answered on the phone: the ringing stopped and it says so.
static bool s_on_phone;

static AppTimer *s_buzz_timer;
static AppTimer *s_ring_out_timer;
// Kyllene was opened for this ring: it quits once the ring's over, unless it was picked up here.
static bool s_for_ring;
static bool s_rang_before;

// What the phone still has to hear: that this rings, and how it ended here (-1 not yet, 0 declined, 1 answered).
static bool s_send_on;
static int s_send_end = -1;
static int s_tries;
static AppTimer *s_retry_timer;
// Quits once the phone has how it ended (or it gave up).
static bool s_quit_when_sent;

static void prv_copy(char *to, size_t size, const char *from) {
  strncpy(to, from ? from : "", size - 1);
  to[size - 1] = '\0';
}

// --- Ringing

static const uint32_t s_buzz[] = { 500, 250, 500 };

#ifdef _PBL_API_EXISTS_speaker_play_notes
// A phone's ring: two notes back and forth, twice. Only where there's a speaker; elsewhere it buzzes alone.
static const SpeakerNote s_tone[] = {
  { .midi_note = 79, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 76, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 79, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 76, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 79, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 76, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 0, .duration_ms = 250 },
  { .midi_note = 79, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 76, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 79, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 76, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 79, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
  { .midi_note = 76, .waveform = SpeakerWaveformSine, .duration_ms = 70 },
};
#endif

static void prv_buzz(void *data) {
  s_buzz_timer = app_timer_register(BUZZ_EVERY_MS, prv_buzz, NULL);
  vibes_enqueue_custom_pattern((VibePattern) { .durations = s_buzz, .num_segments = ARRAY_LENGTH(s_buzz) });
#ifdef _PBL_API_EXISTS_speaker_play_notes
  // Muted (always, or in Quiet Time): it only buzzes.
  if (!speaker_is_muted()) speaker_play_notes(s_tone, ARRAY_LENGTH(s_tone), VOLUME);
#endif
}

static void prv_stop_ringing(void) {
  if (s_buzz_timer) app_timer_cancel(s_buzz_timer);
  s_buzz_timer = NULL;
  if (s_ring_out_timer) app_timer_cancel(s_ring_out_timer);
  s_ring_out_timer = NULL;
  vibes_cancel();
#ifdef _PBL_API_EXISTS_speaker_play_notes
  speaker_stop();
#endif
}

// --- Telling the phone

static void prv_quit(void) {
  s_quit_when_sent = false;
  window_stack_pop_all(true);
}

static void prv_flush(void *data);

static void prv_retry(void) {
  if (s_retry_timer) return;
  if (++s_tries > RETRIES) {
    // The phone's out of reach: it rings out there on its own.
    s_send_on = false;
    s_send_end = -1;
    if (s_quit_when_sent) prv_quit();
    return;
  }
  s_retry_timer = app_timer_register(RETRY_MS, prv_flush, NULL);
}

static void prv_flush(void *data) {
  s_retry_timer = NULL;
  bool sent;
  if (s_send_end >= 0) {
    // How it ended says it rang too.
    s_send_on = false;
    sent = link_ring_end(s_id, s_send_end == 1);
  } else if (s_send_on) {
    sent = link_ring_on(s_id);
  } else {
    return;
  }
  if (!sent) prv_retry();
}

static void prv_tell(void) {
  s_tries = 0;
  if (s_retry_timer) app_timer_cancel(s_retry_timer);
  s_retry_timer = NULL;
  prv_flush(NULL);
}

void ring_on_send_failed(int type) {
  if (type == LINK_RING_ON && s_send_on) prv_retry();
  if (type == LINK_RING_END && s_send_end >= 0) prv_retry();
}

void ring_on_sent(int type) {
  if (type == LINK_RING_ON) {
    s_send_on = false;
    // How it ended came meanwhile: that goes now.
    if (s_send_end >= 0) prv_tell();
  } else if (type == LINK_RING_END) {
    s_send_end = -1;
    if (s_quit_when_sent) prv_quit();
  }
}

// --- The window

static void prv_header_update(Layer *layer, GContext *ctx) {
  layout_draw_header(ctx, layer_get_bounds(layer), "Hermes is calling", s_fill, s_on_fill, 0);
}

static void prv_redraw(void) {
  if (!s_window) return;
  layer_mark_dirty(s_header_layer);
  text_layer_set_text(s_title_layer, s_title);
  text_layer_set_text(s_why_layer, s_on_phone ? "Answer on your phone." : s_why);
  layer_set_hidden(text_layer_get_layer(s_answer_layer), s_on_phone);
  layer_set_hidden(text_layer_get_layer(s_decline_layer), s_on_phone);
}

// The ring's over here: the window goes, and so does Kyllene when it was opened for this ring.
static void prv_close(bool quit) {
  prv_stop_ringing();
  if (s_window) window_stack_remove(s_window, true);
  if (!quit || !s_for_ring) return;
  if (s_send_end >= 0) s_quit_when_sent = true;
  else prv_quit();
}

static void prv_answer(ClickRecognizerRef recognizer, void *context) {
  if (s_on_phone) return;
  if (s_answering == ANSWER_ON_PHONE) {
    // Answered on the phone only, which rings on: this one stops.
    prv_stop_ringing();
    s_on_phone = true;
    prv_redraw();
    return;
  }
  // The request comes next, over the talk screen: Kyllene stays.
  s_for_ring = false;
  s_send_end = 1;
  prv_tell();
  prv_close(false);
}

static void prv_decline(ClickRecognizerRef recognizer, void *context) {
  s_send_end = 0;
  prv_tell();
  prv_close(true);
}

// Back: quiet here only, the phone rings on.
static void prv_back(ClickRecognizerRef recognizer, void *context) {
  prv_close(true);
}

static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_answer);
  window_single_click_subscribe(BUTTON_ID_UP, prv_answer);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_decline);
  window_single_click_subscribe(BUTTON_ID_BACK, prv_back);
}

static TextLayer *prv_text_layer(Layer *root, GRect frame, const char *font, GTextAlignment alignment) {
  TextLayer *layer = text_layer_create(frame);
  text_layer_set_font(layer, fonts_get_system_font(font));
  text_layer_set_background_color(layer, GColorClear);
  text_layer_set_text_alignment(layer, alignment);
  text_layer_set_overflow_mode(layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(layer));
  return layer;
}

static void prv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  window_set_background_color(window, GColorWhite);

  s_header_layer = layer_create(GRect(0, 0, bounds.size.w, HEADER_H));
  layer_set_update_proc(s_header_layer, prv_header_update);
  layer_add_child(root, s_header_layer);

#ifdef PBL_ROUND
  // Centred, in from the edge; what the buttons do goes underneath, there being no room beside them.
  int y = HEADER_H + 8;
  int inset = layout_inset(bounds, y, 34, PAD);
  s_title_layer = prv_text_layer(root, GRect(inset, y, bounds.size.w - inset * 2, 34), FONT_KEY_GOTHIC_28_BOLD, GTextAlignmentCenter);
  y += 36;
  // On the smaller round screen, one line, clear of what's underneath.
  bool small = bounds.size.w <= SMALL_W;
  int why_h = small ? 24 : 56;
  inset = layout_inset(bounds, y, why_h, PAD);
  s_why_layer = prv_text_layer(root, GRect(inset, y, bounds.size.w - inset * 2, why_h),
                               small ? FONT_KEY_GOTHIC_18 : FONT_KEY_GOTHIC_24, GTextAlignmentCenter);
  y = bounds.size.h - 62;
  inset = layout_inset(bounds, y, 20, 0);
  s_answer_layer = prv_text_layer(root, GRect(inset, y, bounds.size.w - inset * 2, 20), FONT_KEY_GOTHIC_18_BOLD, GTextAlignmentCenter);
  text_layer_set_text(s_answer_layer, "Select: answer");
  y += 20;
  inset = layout_inset(bounds, y, 20, 0);
  s_decline_layer = prv_text_layer(root, GRect(inset, y, bounds.size.w - inset * 2, 20), FONT_KEY_GOTHIC_18_BOLD, GTextAlignmentCenter);
  text_layer_set_text(s_decline_layer, "Down: decline");
#else
  int w = bounds.size.w - PAD * 2;
  if (bounds.size.w < SMALL_W) {
    // One line of title, what it's for under it the whole width, and the labels below that.
    s_title_layer = prv_text_layer(root, GRect(PAD, HEADER_H + 2, w, 30), FONT_KEY_GOTHIC_24_BOLD, GTextAlignmentLeft);
    s_why_layer = prv_text_layer(root, GRect(PAD, HEADER_H + 32, w, 52), FONT_KEY_GOTHIC_24, GTextAlignmentLeft);
  } else {
    s_title_layer = prv_text_layer(root, GRect(PAD, HEADER_H + 6, w, 64), FONT_KEY_GOTHIC_28_BOLD, GTextAlignmentLeft);
    // Clear of the labels at the right.
    s_why_layer = prv_text_layer(root, GRect(PAD, HEADER_H + 70, w - LABELS_W, 56), FONT_KEY_GOTHIC_24, GTextAlignmentLeft);
  }
  // By the buttons they're for: Select (and Up) answer, Down declines.
  s_answer_layer = prv_text_layer(root, GRect(PAD, bounds.size.h - 50, w, 24), FONT_KEY_GOTHIC_18_BOLD, GTextAlignmentRight);
  text_layer_set_text(s_answer_layer, "Answer");
  s_decline_layer = prv_text_layer(root, GRect(PAD, bounds.size.h - 26, w, 24), FONT_KEY_GOTHIC_18_BOLD, GTextAlignmentRight);
  text_layer_set_text(s_decline_layer, "Decline");
#endif
  text_layer_set_text_color(s_decline_layer, PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack));
  prv_redraw();
}

static void prv_unload(Window *window) {
  text_layer_destroy(s_decline_layer);
  text_layer_destroy(s_answer_layer);
  text_layer_destroy(s_why_layer);
  text_layer_destroy(s_title_layer);
  layer_destroy(s_header_layer);
  window_destroy(s_window);
  s_window = NULL;
}

static void prv_rang_out(void *data) {
  s_ring_out_timer = NULL;
  prv_close(true);
}

void ring_show(const LinkRing *ring, GColor fill, GColor on_fill) {
  // The same ring again (sent once more as Kyllene opened): it rings on as it is.
  if (s_window && strcmp(s_id, ring->request_id) == 0) return;
  if (!s_rang_before) s_for_ring = launch_reason() == APP_LAUNCH_PHONE;
  s_rang_before = true;
  s_fill = fill;
  s_on_fill = on_fill;
  prv_copy(s_id, sizeof(s_id), ring->request_id);
  prv_copy(s_title, sizeof(s_title), ring->title);
  prv_copy(s_why, sizeof(s_why), ring->agent ? "Wants to talk." : ring->approval ? "Needs your approval." : "Has a question.");
  s_answering = ring->answering;
  s_on_phone = false;
  s_send_end = -1;
  s_send_on = true;
  s_quit_when_sent = false;
  prv_tell();

  prv_stop_ringing();
  if (!s_window) {
    s_window = window_create();
    window_set_click_config_provider(s_window, prv_click_config);
    window_set_window_handlers(s_window, (WindowHandlers) { .load = prv_load, .unload = prv_unload });
    window_stack_push(s_window, true);
  } else {
    prv_redraw();
  }
  prv_buzz(NULL);
  s_ring_out_timer = app_timer_register(RING_MS, prv_rang_out, NULL);
}

void ring_end(const char *request_id) {
  if (strcmp(s_id, request_id) != 0) return;
  s_send_on = false;
  if (s_window) prv_close(true);
}
