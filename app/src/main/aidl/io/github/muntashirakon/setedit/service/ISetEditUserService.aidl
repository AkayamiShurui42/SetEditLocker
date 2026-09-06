package io.github.muntashirakon.setedit.service;

interface ISetEditUserService {
    void destroy() = 16777114;
    String execute(in String[] command) = 1;
}
