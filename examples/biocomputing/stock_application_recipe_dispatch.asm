; Explicit MATERIAL REQUEST variant of the order matcher, not the read-only preview.
; F selection then validated V page on x0, original source and standard inputs to M on x1.
TYPE R FORMAT recipe 1
TYPE Opt OPTIONAL R
TYPE Order RECORD k ANY d ANY n ANY s ANY
TYPE Candidate RECORD o BOOLEAN r Opt n INTEGER w ANY
TYPE Ref RECORD page ANY fingerprint ANY
TYPE Pairs LIST ANY
TYPE Work RECORD s ANY a ANY n ANY m ANY
REG order Order
REG page Candidate
REG recipe Opt
REG work Work
REG ref Ref
REG first Pairs
REG second Pairs
REG raw ANY
REG k ANY
REG p ANY
REG f ANY
REG d ANY
REG n ANY
REG source ANY
REG rows ANY
REG address ANY
REG what TEXT
REG flag BOOLEAN
REG level INTEGER
REG reply ANY
CONST level 15
XBUS.READ order x0
SIO.WRITE p0 level
XBUS.READ page x0
CONST level 0
SIO.WRITE p0 level
FIELD flag page o
CONST reply {"code":"invalid_recipe"}
JFALSE flag done
FIELD recipe page r
FMT.PROJECT raw recipe recipe 1
FIELD k raw pattern_id
FIELD p raw page
FIELD f raw fingerprint
RECORD.MAKE ref page p fingerprint f
LIST.MAKE first k ref
FIELD k order k
FIELD d order d
LIST.MAKE second k d
VALUE.EQUALS flag first second
CONST reply {"code":"recipe_changed"}
JFALSE flag done
FIELD k raw type
CONST what "standard"
VALUE.EQUALS flag k what
CONST reply {"code":"stage_selection_required"}
JFALSE flag done
FIELD rows raw inputs
FIELD d raw body
FIELD address d address
FIELD source order s
FIELD n order n
RECORD.MAKE work s source a address n n m rows
XBUS.WRITE x1 work
CONST reply {"code":"forwarded"}
done:
