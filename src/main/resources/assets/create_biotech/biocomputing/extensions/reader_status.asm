; Reader status query v1. Sends once; the receive block may obtain a later reply.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE empty RECORD
TYPE message RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments empty
REG target location
REG packet message
REG arguments empty
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "reader_command"
CONST version 1
CONST operation "status"
CONST arguments {}
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 packet queued
RET