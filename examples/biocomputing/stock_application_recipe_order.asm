; x0: selection first; after p0=15, V supplies the reread recipe. x1: P only.
TYPE R FORMAT recipe 1
TYPE Opt OPTIONAL R
TYPE Order RECORD k ANY d ANY n ANY s ANY
TYPE Candidate RECORD o BOOLEAN r Opt n INTEGER w ANY
TYPE Forward RECORD o BOOLEAN r Opt n ANY w ANY
TYPE Pairs LIST ANY
TYPE Match RECORD code TEXT source ANY batches ANY
TYPE Ref RECORD page ANY fingerprint ANY
REG order Order
REG page Candidate
REG forward Forward
REG match Match
REG reply ANY
REG recipe Opt
REG expected Pairs
REG actual Pairs
REG v ANY
REG key ANY
REG fp ANY
REG pos ANY
REG n ANY
REG source ANY
REG ref Ref
REG group ANY
REG flag BOOLEAN
REG code TEXT
REG level INTEGER
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
FMT.PROJECT v recipe recipe 1
FIELD key v pattern_id
FIELD pos v page
FIELD fp v fingerprint
RECORD.MAKE ref page pos fingerprint fp
LIST.MAKE actual key ref
FIELD key order k
FIELD v order d
LIST.MAKE expected key v
VALUE.EQUALS flag expected actual
CONST reply {"code":"recipe_changed"}
JFALSE flag done
FIELD n order n
FIELD group page w
RECORD.MAKE forward o flag r recipe n n w group
XBUS.WRITE x1 forward
FIELD source order s
CONST code "matched"
RECORD.MAKE match code code source source batches n
MOV reply match
done:
