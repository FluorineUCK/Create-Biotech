; Queue one speaker media stop request. No read-head operations, polling or retries.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE payload RECORD device TEXT session TEXT revision INTEGER
TYPE packet RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments payload
REG target location
REG command packet
REG arguments payload
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "speaker_media_command"
CONST version 1
CONST operation "stop"
RECORD.MAKE arguments device device session session revision revision
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE command kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 command queued
RET
