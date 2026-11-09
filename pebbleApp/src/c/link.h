#pragma once

// What Kyllene on the phone and this watchapp say to each other: the message types and keys of
// shared/wearlink's PebbleLink.kt (keys from package.json's messageKeys), read into plain structs.

#include <pebble.h>

// ReplyPhase, in its order.
typedef enum {
  PHASE_TRANSCRIBING,
  PHASE_SENDING,
  PHASE_WORKING,
  PHASE_WAITING_ON_PHONE,
  PHASE_DONE,
  PHASE_FAILED,
} Phase;

#define LINK_FLAG_SIGNED_IN 1
#define LINK_FLAG_CONNECTED 2
#define LINK_FLAG_FAILED 4
// Kyllene's Pebble switch is off on the phone.
#define LINK_FLAG_OFF 8

// One piece of a turn: its state, and text to place at byte offset of the reply.
typedef struct {
  const char *ask_id;
  int seq;
  Phase phase;
  const char *chat_id;   // NULL until the phone names it
  const char *title;     // NULL when unchanged
  const char *activity;  // NULL for none
  const char *error;     // NULL for none
  const char *said;      // what was said, when the phone tells it (a chat's last exchange); else NULL
  const char *scope;     // the chat's gateway and profile, when the phone says; else NULL
  const char *text;
  int text_offset;
  int text_total;
} LinkReply;

typedef struct {
  int flags;
  bool has_accent;
  const char *accent_rev;
  uint32_t fill, text, on_fill, dim;  // 0xRRGGBB
} LinkStatus;

// One of the recent chats, [index] of [count]; a count of 0 is an empty list, or [error] when it couldn't be read.
typedef struct {
  int index;
  int count;
  const char *chat_id;
  const char *title;
  const char *ago;    // NULL when the chat has no activity time
  const char *scope;  // the list's gateway and profile, NULL when the phone doesn't say
  const char *error;  // NULL for none
} LinkChatItem;

// How a request is answered on the watch.
typedef enum { ANSWER_ON_PHONE, ANSWER_CHOICES, ANSWER_CHOICES_OR_TEXT } Answering;

// An approval or a question waiting in a chat; [choices] one per line.
typedef struct {
  const char *request_id;
  const char *chat_id;  // NULL when unknown
  const char *title;    // the chat's, NULL when unknown
  bool approval;
  const char *heading;
  const char *detail;
  const char *choices;
  Answering answering;
} LinkRequest;

// Hermes calling for request [request_id], waiting in a chat; [answering] says what Answer does here.
typedef struct {
  const char *request_id;
  const char *chat_id;  // empty for a call from the agent with no chat
  const char *title;
  bool approval;
  bool agent;
  Answering answering;
} LinkRing;

typedef struct {
  void (*on_reply)(const LinkReply *reply);
  void (*on_request)(const LinkRequest *request);
  // Over: answered (here or elsewhere), or [error] (else NULL) says why the watch's answer didn't go.
  void (*on_request_end)(const char *request_id, const char *error);
  void (*on_chat_item)(const LinkChatItem *item);
  void (*on_status)(const LinkStatus *status);
  void (*on_ring)(const LinkRing *ring);
  // The prompts to offer, one per line, and their revision.
  void (*on_prompts)(const char *revision, const char *lines);
  // The ring for [request_id] is over on the phone: answered, declined or rung out there.
  void (*on_ring_end)(const char *request_id);
  // A message to the phone that didn't go, by its type.
  void (*on_send_failed)(int type);
  // A message the phone took, by its type.
  void (*on_sent)(int type);
} LinkHandlers;

enum {
  LINK_ASK = 1,
  LINK_STOP = 2,
  LINK_CHATS_REQ = 3,
  LINK_STATUS_REQ = 4,
  LINK_RING_ON = 5,
  LINK_RING_END = 6,
  LINK_RESYNC = 7,
  LINK_HISTORY_REQ = 8,
  LINK_ANSWER = 9,
};

// Opens AppMessage with the inbox the phone's messages are sized for.
void link_open(LinkHandlers handlers);

// Each is false when the outbox was busy and nothing went.
// [scope] is the gateway and profile [chat_id] belongs to, as the phone said: one from elsewhere is refused there.
bool link_ask(const char *ask_id, const char *text, const char *chat_id, const char *scope);
bool link_stop(const char *ask_id);
// With the accent and the prompts this has, so the phone sends either only when they changed.
bool link_status_request(const char *accent_rev, const char *prompts_rev);
bool link_chats_request(void);
// [chat_id]'s last exchange, which comes as a finished reply.
bool link_history_request(const char *chat_id, const char *scope);
bool link_resync(const char *ask_id);
// Choice [index] of those shown, or (index -1) [text] dictated.
bool link_answer(const char *request_id, int index, const char *text);

// This watch rings for [request_id].
bool link_ring_on(const char *request_id);
// Picked up here ([answered]) or declined.
bool link_ring_end(const char *request_id, bool answered);

// Whether the Pebble phone app is connected at all.
bool link_phone_connected(void);
