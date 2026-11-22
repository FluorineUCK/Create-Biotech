; Send one read-only chat adapter status request. No emission, polling or retries.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE payload RECORD
TYPE packet RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments payload
REG target location
REG command packet
REG arguments payload
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "chat_output_command"
CONST version 1
CONST operation "status"
CONST arguments {}
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE command kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 command queued
RET
