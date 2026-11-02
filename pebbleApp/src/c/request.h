#pragma once

// An approval or a question from Hermes, answered on the watch (when answering on the Pebble is on in Kyllene).

#include "link.h"

// Shows [request] over whatever is on screen, or updates it when it's the one showing.
void request_show(const LinkRequest *request, GColor fill, GColor on_fill);

// Request [request_id] is over: the window closes, or says why the answer didn't go ([error], else NULL).
void request_end(const char *request_id, const char *error);
