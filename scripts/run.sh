#!/bin/bash

PROJECT_ROOT=..
TESTS=(test_property.py test_layout.py)
SELENDROID_VERSION=0.16.0

HTTP_SERVER_PID=NULL
SELENDROID_PID=NULL
SELENDROID_JAR=selendroid-standalone-$SELENDROID_VERSION-with-dependencies.jar
APK_TO_TEST=$PROJECT_ROOT/app/build/outputs/apk/app-debug.apk

function get_selendroid {
    if [ ! -f $SELENDROID_JAR ]
    then
        wget https://github.com/selendroid/selendroid/releases/download/$SELENDROID_VERSION/$SELENDROID_JAR
    fi
}

function build_apk {
    if [ ! -f $APK_TO_TEST ]
    then
        (cd $PROJECT_ROOT;./gradlew clean assembleDebug)
    fi
}

function start_http_server {
    python http_server.py&
    HTTP_SERVER_PID=$!
}

function start_selendroid_server {
    java -jar $SELENDROID_JAR -app $APK_TO_TEST&
    SELENDROID_PID=$!
}

function run_test {
    total_cnt=0
    failure_cnt=0
    for test_file in ${TESTS[@]}
    do
        python $test_file
        if [ $? != 0 ]
        then
            let failure_cnt+=1
        fi
        let total_cnt+=1
    done

    bold=`tput bold`
    green=`tput setaf 2`
    red=`tput setaf 1`
    normal=`tput sgr0`
    echo ""
    echo "${bold}Total test suite(s): ${total_cnt}${normal}"
    echo "${green}Passed test suite(s): $((total_cnt-failure_cnt))${normal}"
    echo "${red}Failed test suite(s): ${failure_cnt}${normal}"
}

function clean_up {
    if [ $HTTP_SERVER_PID != NULL ]
    then
        kill $HTTP_SERVER_PID
    fi

    if [ $SELENDROID_PID != NULL ]
    then
        kill $SELENDROID_PID
    fi
}

get_selendroid
build_apk
start_http_server
start_selendroid_server
# wait for server initialization
sleep 10
run_test
clean_up
