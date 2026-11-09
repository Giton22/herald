// Kyllene on a Pebble: talk to Hermes through Kyllene on the phone, which holds the gateway connection and the
// sign-in. See docs/pebble.md.

#include <pebble.h>
#include "link.h"
#include "talk.h"
#include "chats.h"
#include "request.h"
#include "ring.h"

static void prv_send_failed(int type) {
  talk_on_send_failed(type);
  ring_on_send_failed(type);
  request_on_send_failed(type);
}

int main(void) {
  link_open((LinkHandlers) {
    .on_reply = talk_on_reply,
    .on_chat_item = chats_on_item,
    .on_request = talk_on_request,
    .on_request_end = request_end,
    .on_status = talk_on_status,
    .on_ring = talk_on_ring,
    .on_prompts = talk_on_prompts,
    .on_ring_end = ring_end,
    .on_send_failed = prv_send_failed,
    .on_sent = ring_on_sent,
  });
  talk_init();
  app_event_loop();
  talk_deinit();
}
