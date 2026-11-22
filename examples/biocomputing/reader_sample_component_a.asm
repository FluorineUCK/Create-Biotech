; Use the tested B transport and C reply matcher. Exactly one query, then at most one capture.
TYPE address_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target address_type request TEXT operation TEXT arguments ANY
TYPE component_list LIST TEXT
TYPE outcome_type RECORD ok BOOLEAN value ANY
TYPE sample_type RECORD device ANY session ANY components component_list
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
REG pending BOOLEAN
REG components component_list
CONST kind "reader_command"
CONST version 1
CONST operation "status"
CONST arguments {}
CONST expected "ok"
CONST pending true
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
JFALSE pending done
FIELD state reply state
FIELD device state device
FIELD session state session
LIST.MAKE components component
RECORD.MAKE sample_arguments device device session session components components
MOV arguments sample_arguments
CONST operation "sample"
CONST pending false
JMP send
done:
