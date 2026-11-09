#pragma once

// Hermes calling for an answer: the watch rings, buzzing and on the speaker, along with the phone. Answer shows the
// request here when the phone says it's answered here; else it stops the ringing and says to answer on the phone.

#include "link.h"

// Rings for [ring] over whatever is on screen, in the talk screen's colours.
void ring_show(const LinkRing *ring, GColor fill, GColor on_fill);

// The phone says the ring for [request_id] is over.
void ring_end(const char *request_id);

// What the link says of the messages sent: a ring's go again until the phone has them.
void ring_on_send_failed(int type);
void ring_on_sent(int type);
