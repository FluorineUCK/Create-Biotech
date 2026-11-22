; Queue one speaker media play request. No read-head operations, polling or retries.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE entrytoken RECORD medium TEXT entry TEXT revision INTEGER
TYPE libraryref RECORD library location device TEXT sequence INTEGER version entrytoken
TYPE payload RECORD device TEXT session TEXT revision INTEGER reference libraryref
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
CONST operation "play"
RECORD.MAKE arguments device device session session revision revision reference reference
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE command kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 command queued
RET
