; Read-only status request. All protocol selection is editable software.
; service: reader / stock_application / production. target: adapter position.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE call_type RECORD p packet_type e TEXT
TYPE result_type RECORD r ANY
REG packet packet_type
REG call call_type
REG result result_type
REG kind TEXT
REG expected TEXT
REG selected TEXT
REG version INTEGER
REG request TEXT
REG operation TEXT
REG arguments ANY
REG reply ANY
REG matches BOOLEAN
CONST selected "reader"
VALUE.EQUALS matches service selected
JFALSE matches stock
CONST kind "reader_command"
CONST expected "reader_reply"
JMP send
stock:
CONST selected "stock_application"
VALUE.EQUALS matches service selected
JFALSE matches material
CONST kind "stock_application_command"
CONST expected "stock_application_reply"
JMP send
material:
CONST selected "production"
VALUE.EQUALS matches service selected
JFALSE matches unknown
CONST kind "production_command"
CONST expected "production_reply"
send:
CONST version 1
CONST operation "status"
CONST arguments {}
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ result x0
FIELD reply result r
JMP done
unknown:
CONST reply {"code":"unsupported_service"}
done:
