; D: explicitly sample the real Reader, then ACK one frozen head
; through C/B/A and the external output. Run only after confirming an input.
; An empty sample, transport error or rejection ends the run without retransmit.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE call_type RECORD k BOOLEAN t target_type o TEXT a ANY
TYPE result_type RECORD r ANY
TYPE ids_type LIST TEXT
TYPE sample_args RECORD device ANY session ANY components ids_type
REG sample sample_args
REG call call_type
REG result result_type
REG input ANY
REG reply ANY
REG is_reader BOOLEAN
REG sampling BOOLEAN
REG op TEXT
REG args ANY
REG state ANY
REG device ANY
REG session ANY
REG ok BOOLEAN
REG required_field TEXT
REG head_key TEXT
REG ids ids_type
REG view ANY
REG components ANY

CONST input null

CONST required_field "state"
CONST head_key "head"
CONST is_reader true
CONST sampling false
CONST op "status"
CONST args {}
send:
RECORD.MAKE call k is_reader t reader o op a args
XBUS.WRITE x0 call
XBUS.READ result x0
FIELD reply result r
JFALSE is_reader done
HAS.FIELD ok reply required_field
JFALSE ok done
JFALSE sampling sample_setup
FIELD view reply value
FIELD components view components
FIELD view components create_biotech:stock
HAS.FIELD ok view head_key
JFALSE ok done
FIELD input view head
MOV args view
MOV reader output
CONST is_reader false
CONST op "ack"
JMP send
sample_setup:
FIELD state reply state
FIELD device state device
FIELD session state session
CONST ids ["create_biotech:stock"]
RECORD.MAKE sample device device session session components ids
MOV args sample
CONST required_field "value"
CONST op "sample"
CONST sampling true
JMP send
done:
