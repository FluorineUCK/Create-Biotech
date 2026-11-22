; Query the device first; queue admission is not its reply.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE payload RECORD
TYPE message RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments payload
REG target location
REG packet message
REG arguments payload
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "text_output_command"
CONST version 1
CONST operation "status"
CONST arguments {}
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 packet queued
RET
