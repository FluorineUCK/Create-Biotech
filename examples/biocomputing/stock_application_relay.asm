; C's single managed relay, launched by ordinary TaskStart after publish replies.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE call_type RECORD k BOOLEAN t target_type o TEXT a ANY
TYPE result_type RECORD r ANY
REG call call_type
REG result result_type
REG ok BOOLEAN
REG probe INTEGER
REG readable INTEGER
REG delay INTEGER
CONST readable 15
CONST delay 10
relay:
SLEEP delay
XBUS.STATUS probe x1
EQUAL_INTEGER ok probe readable
JFALSE ok relay
XBUS.READ call x1
XBUS.WRITE x0 call
XBUS.READ result x0
XBUS.WRITE x1 result
JMP relay
