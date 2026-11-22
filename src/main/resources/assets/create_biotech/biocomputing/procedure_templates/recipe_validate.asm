TYPE R FORMAT recipe 1
TYPE maybe_recipe OPTIONAL R
TYPE recipe_check RECORD ok BOOLEAN value maybe_recipe
REG checked recipe_check
FMT.CHECK checked source recipe 1
