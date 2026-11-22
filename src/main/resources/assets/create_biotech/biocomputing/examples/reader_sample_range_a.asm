TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE outcome_type RECORD ok BOOLEAN value ANY
TYPE sample_type RECORD device ANY session ANY component TEXT offset INTEGER count INTEGER
REG packet packet_type
REG outcome outcome_type
REG kind TEXT
REG version INTEGER
REG request TEXT
REG operation TEXT
REG arguments ANY
REG sample_arguments sample_type
REG expected TEXT
REG reply ANY
REG code ANY
REG state ANY
REG device ANY
REG session ANY
REG flag BOOLEAN
REG range_pending BOOLEAN
CONST kind "reader_command"
CONST version 1
CONST operation "status"
CONST arguments {}
CONST expected "ok"
CONST range_pending true
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
XBUS.WRITE x0 packet
XBUS.READ outcome x0
FIELD flag outcome ok
FIELD reply outcome value
JFALSE flag done
FIELD code reply code
VALUE.EQUALS flag code expected
JFALSE flag done
JFALSE range_pending done
FIELD state reply state
FIELD device state device
FIELD session state session
RECORD.MAKE sample_arguments device device session session component component offset offset count count
MOV arguments sample_arguments
CONST operation "sample_range"
CONST range_pending false
JMP send
done:
