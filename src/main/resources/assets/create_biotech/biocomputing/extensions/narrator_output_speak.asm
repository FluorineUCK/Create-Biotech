; Submit one narration command with the token from a matching status reply.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE payload RECORD device TEXT session TEXT revision INTEGER text TEXT
TYPE packet RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments payload
REG target location
REG command packet
REG arguments payload
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "narrator_output_command"
CONST version 1
CONST operation "speak"
RECORD.MAKE arguments device device session session revision revision text text
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE command kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 command queued
RET
