#include "chats.h"
#include "layout.h"

// RelayChats' CHAT_LIST_SIZE: the phone sends no more.
#define MAX_CHATS 15

typedef struct {
  char id[64];
  char title[64];
  char ago[8];
} Chat;

static Window *s_window;
static MenuLayer *s_menu;
static ChatPicked s_picked;
static GColor s_fill;
static GColor s_on_fill;
static char s_current[64];
static Chat s_chats[MAX_CHATS];
static int s_count;
// Until the phone's list has all come: shown as one row saying so.
static bool s_loading;
static char s_error[128];
// The gateway and profile the listed chats are in: PebbleLink.MAX_SCOPE_BYTES.
static char s_scope[208];

static void prv_copy(char *to, size_t size, const char *from) {
  strncpy(to, from ? from : "", size - 1);
  to[size - 1] = '\0';
}

// A row for "New chat", then one per chat; while loading or on an error, one row saying so instead of the chats.
static uint16_t prv_rows(MenuLayer *menu, uint16_t section, void *data) {
  return 1 + ((s_loading || s_error[0] || s_count == 0) ? 1 : s_count);
}

static void prv_draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *data) {
  if (index->row == 0) {
    menu_cell_basic_draw(ctx, cell, "New chat", s_current[0] ? NULL : "Talking here", NULL);
    return;
  }
  if (s_loading) {
    menu_cell_basic_draw(ctx, cell, "Loading...", NULL, NULL);
    return;
  }
  if (s_error[0] || s_count == 0) {
    GRect bounds = layer_get_bounds(cell);
    graphics_draw_text(ctx, s_error[0] ? s_error : "No chats yet.", fonts_get_system_font(FONT_KEY_GOTHIC_18),
                       GRect(PBL_IF_ROUND_ELSE(18, 6), 0, bounds.size.w - PBL_IF_ROUND_ELSE(36, 12), bounds.size.h),
                       GTextOverflowModeTrailingEllipsis, LAYOUT_ALIGN, NULL);
    return;
  }
  const Chat *chat = &s_chats[index->row - 1];
  char subtitle[32];
  bool here = chat->id[0] && strcmp(chat->id, s_current) == 0;
  snprintf(subtitle, sizeof(subtitle), "%s%s%s", here ? "Talking here" : "", here && chat->ago[0] ? " - " : "", chat->ago);
  menu_cell_basic_draw(ctx, cell, chat->title[0] ? chat->title : "Untitled", subtitle[0] ? subtitle : NULL, NULL);
}

static int16_t prv_row_height(MenuLayer *menu, MenuIndex *index, void *data) {
  return (index->row > 0 && (s_error[0] && !s_loading)) ? 66 : 50;
}

static void prv_select(MenuLayer *menu, MenuIndex *index, void *data) {
  if (index->row == 0) {
    s_picked("", "", "");
  } else if (!s_loading && !s_error[0] && index->row - 1 < s_count && s_chats[index->row - 1].id[0]) {
    // A row whose chat never came has no id, which would pick a new chat.
    s_picked(s_chats[index->row - 1].id, s_chats[index->row - 1].title, s_scope);
  } else {
    return;
  }
  window_stack_remove(s_window, true);
}

void chats_on_item(const LinkChatItem *item) {
  if (!s_window) return;
  if (item->count == 0) {
    s_count = 0;
    s_loading = false;
    prv_copy(s_error, sizeof(s_error), item->error);
  } else if (item->index >= 0 && item->index < MAX_CHATS) {
    Chat *chat = &s_chats[item->index];
    prv_copy(chat->id, sizeof(chat->id), item->chat_id);
    prv_copy(chat->title, sizeof(chat->title), item->title);
    prv_copy(chat->ago, sizeof(chat->ago), item->ago);
    prv_copy(s_scope, sizeof(s_scope), item->scope);
    s_count = item->count < MAX_CHATS ? item->count : MAX_CHATS;
    // They come in order: the last one ends the list.
    if (item->index >= s_count - 1) s_loading = false;
  }
  menu_layer_reload_data(s_menu);
}

static void prv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_rows,
    .draw_row = prv_draw_row,
    .get_cell_height = prv_row_height,
    .select_click = prv_select,
  });
  menu_layer_set_highlight_colors(s_menu, s_fill, s_on_fill);
  menu_layer_set_click_config_onto_window(s_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void prv_unload(Window *window) {
  menu_layer_destroy(s_menu);
  window_destroy(s_window);
  s_window = NULL;
}

void chats_open(const char *current, GColor fill, GColor on_fill, ChatPicked picked) {
  if (s_window) return;
  s_picked = picked;
  s_fill = fill;
  s_on_fill = on_fill;
  prv_copy(s_current, sizeof(s_current), current);
  s_count = 0;
  // An item that never comes leaves an empty row, not a chat from the list before.
  memset(s_chats, 0, sizeof(s_chats));
  s_error[0] = '\0';
  s_loading = link_chats_request();
  if (!s_loading) prv_copy(s_error, sizeof(s_error), "Couldn't ask your phone. Try again.");
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) { .load = prv_load, .unload = prv_unload });
  window_stack_push(s_window, true);
}
