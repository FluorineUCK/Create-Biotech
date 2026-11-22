TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE outcome_type RECORD ok BOOLEAN value ANY
TYPE items_type LIST ANY
TYPE data_type RECORD items items_type
TYPE submit_type RECORD device ANY session ANY order TEXT data data_type
TYPE query_type RECORD order TEXT
REG packet packet_type
REG outcome outcome_type
REG kind TEXT
REG version INTEGER
REG request TEXT
REG operation TEXT
REG arguments ANY
REG data data_type
REG submission submit_type
REG query query_type
REG reply ANY
REG state ANY
REG device ANY
REG session ANY
REG expected TEXT
REG status TEXT
REG flag BOOLEAN
CONST kind "production_command"
CONST version 1
CONST status "status"
CONST operation "submit"
VALUE.EQUALS flag action operation
JFALSE flag query_order
MOV operation status
CONST arguments {}
JMP send
query_order:
CONST operation "order"
RECORD.MAKE query order job
MOV arguments query
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
XBUS.WRITE x0 packet
XBUS.READ outcome x0
FIELD flag outcome ok
FIELD reply outcome value
JFALSE flag done
VALUE.EQUALS flag operation status
JFALSE flag done
FIELD state reply code
CONST expected "ok"
VALUE.EQUALS flag state expected
JFALSE flag done
FIELD state reply state
FIELD device state device
FIELD session state session
RECORD.MAKE data items items
RECORD.MAKE submission device device session session order job data data
MOV arguments submission
CONST operation "submit"
JMP send
done:
