; Queue one sample request. device and session come from a Reader status reply.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE componentList LIST TEXT
TYPE sampleArgs RECORD device TEXT session TEXT components componentList
TYPE message RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments sampleArgs
REG target location
REG packet message
REG arguments sampleArgs
REG components componentList
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "reader_command"
CONST version 1
CONST operation "sample"
CORRELATION request
RECORD.MAKE target x x y y z z
LIST.MAKE components component
RECORD.MAKE arguments device device session session components components
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 packet queued
RET
