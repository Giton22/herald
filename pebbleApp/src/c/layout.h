#pragma once

// Where things go on each watch's screen. On a round one, a row starts further in the nearer it is to the top or
// bottom, text is centred, and the header is taller, its top being narrow.

#include <pebble.h>

#define LAYOUT_HEADER_H PBL_IF_ROUND_ELSE(46, 30)
#define LAYOUT_ALIGN PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft)

// How far in from each side the row from [top] to [top + height] of [screen] starts: [pad], and on a round screen
// as far again as its narrower edge needs.
int layout_inset(GRect screen, int top, int height, int pad);

// The window's header in [fill], with [title] in [on_fill]: along its bottom, centred, on a round screen.
void layout_draw_header(GContext *ctx, GRect bounds, const char *title, GColor fill, GColor on_fill, int right_room);

// Places [layer], in [scroll], at [x], [y], [w] wide and as tall as its text: on a round screen the text follows the
// screen's edge, a page at a time. [font] is the layer's. Its height, 0 for no text.
int layout_fit(TextLayer *layer, GFont font, ScrollLayer *scroll, int x, int y, int w);
