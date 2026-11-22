; Called synchronously after the public Scratch Ask block accepted one answer.
; x0 <-> ROM query station x1. No background receiver and no retry.
TYPE Query RECORD q TEXT i INTEGER k TEXT
TYPE Reply RECORD c TEXT n INTEGER r Query v ANY
TYPE Pages LIST ANY
TYPE Panel RECORD q TEXT c TEXT rows Pages
TYPE Recipe FORMAT recipe 2
TYPE MaybeRecipe OPTIONAL Recipe
TYPE Checked RECORD ok BOOLEAN value MaybeRecipe
REG q TEXT
REG k TEXT
REG i INTEGER
REG next INTEGER
REG req Query
REG echo Query
REG reply Reply
REG c TEXT
REG match TEXT
REG end TEXT
REG ok BOOLEAN
REG v ANY
REG checked Checked
REG rows Pages
REG panel Panel
TERMINAL.ANSWER q
CONST i 0
CONST match "match"
CONST end "end"
LIST.MAKE rows
; One identity per search; the echoed cursor also has to agree for each page.
CORRELATION k
ask:
RECORD.MAKE req q q i i k k
XBUS.WRITE x0 req
XBUS.READ reply x0
FIELD echo reply r
VALUE.EQUALS ok echo req
JFALSE ok stale
FIELD c reply c
FIELD next reply n
VALUE.EQUALS ok c match
JFALSE ok ended
GREATER_INTEGER ok next i
JFALSE ok bad_cursor
FIELD v reply v
DROP reply
FMT.CHECK checked v recipe 2
FIELD ok checked ok
DROP checked
JFALSE ok bad_page
LIST.APPEND rows rows v
DROP v
MOV i next
JMP ask
ended:
VALUE.EQUALS ok c end
JFALSE ok reject
LESS_INTEGER ok next i
JFALSE ok publish
bad_cursor:
CONST c "invalid_cursor"
JMP reject
bad_page:
CONST c "invalid_recipe"
JMP reject
stale:
CONST c "unmatched_reply"
reject:
; Never publish a successful-looking prefix from a failed or unrelated search.
LIST.MAKE rows
publish:
RECORD.MAKE panel q q c c rows rows
