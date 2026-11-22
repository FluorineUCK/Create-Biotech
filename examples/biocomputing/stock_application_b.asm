; B: the shared command builder. x0=A, x1=C. Names k/t/o/a belong to this
; editable XBus document only; none is a host or private register convention.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE call_type RECORD k BOOLEAN t target_type o TEXT a ANY
TYPE result_type RECORD r ANY
TYPE ack_args RECORD device ANY session ANY revision ANY sequence ANY
REG ack ack_args
REG call call_type
REG packet packet_type
REG result result_type
REG target target_type
REG version INTEGER
REG request TEXT
REG kind TEXT
REG op TEXT
REG args ANY
REG stage TEXT
REG reader BOOLEAN
REG is_ack BOOLEAN
REG state ANY
REG head ANY
REG device ANY
REG session ANY
REG revision ANY
REG sequence ANY
REG probe INTEGER
CONST version 1
CONST stage "ack"
next:
SLEEP version
XBUS.STATUS probe x1
LESS_INTEGER is_ack version probe
JFALSE is_ack next
XBUS.READ call x1
FIELD reader call k
FIELD target call t
FIELD op call o
FIELD args call a
VALUE.EQUALS is_ack op stage
JFALSE is_ack encode
FIELD state args state
FIELD head args head
FIELD device state device
FIELD session state session
FIELD revision state revision
FIELD sequence head sequence
RECORD.MAKE ack device device session session revision revision sequence sequence
MOV args ack
encode:
CONST kind "stock_application_command"
JFALSE reader send
CONST kind "reader_command"
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
XBUS.WRITE x0 packet
XBUS.READ result x0
XBUS.WRITE x1 result
JMP next
