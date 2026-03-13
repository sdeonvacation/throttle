#!/bin/bash

# Throttle Simulator - Test Runner
# Run all tests or individual scenarios

BASE_URL="http://localhost:8080/api/simulator"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=====================================${NC}"
echo -e "${BLUE}Throttle Simulator${NC}"
echo -e "${BLUE}=====================================${NC}"
echo ""

# Check if server is running
if ! curl -s "$BASE_URL/health" > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Simulator is not running!${NC}"
    echo "Please start the application first:"
    echo "  cd simulator"
    echo "  mvn spring-boot:run"
    exit 1
fi

echo -e "${GREEN}✓ Simulator is running${NC}"
echo ""

# Function to run a test
run_test() {
    local test_name=$1
    local endpoint=$2

    echo -e "${BLUE}Running: $test_name${NC}"
    response=$(curl -s -X POST "$BASE_URL$endpoint")
    success=$(echo "$response" | grep -o '"success":[^,]*' | cut -d':' -f2)
    duration=$(echo "$response" | grep -o '"duration":[0-9]*' | cut -d':' -f2)

    if [ "$success" = "true" ]; then
        echo -e "${GREEN}✓ PASSED${NC} (${duration}ms)"
    else
        echo -e "${RED}✗ FAILED${NC}"
        echo "$response" | grep -o '"error":"[^"]*"'
    fi
    echo ""
}

# Check command line argument
if [ $# -eq 0 ]; then
    # Run all tests
    echo "Running all test scenarios..."
    echo ""

    run_test "1. Normal Operation" "/run/normal-operation"
    sleep 2

    run_test "2. Resource Spike" "/run/resource-spike"
    sleep 2

    run_test "3. Sustained Load" "/run/sustained-load"
    sleep 2

    run_test "4. Memory Pressure" "/run/memory-pressure"
    sleep 2

    run_test "5. Task Killing" "/run/task-killing"
    sleep 2

    run_test "6. Priority Scheduling" "/run/priority-scheduling"
    sleep 2

    run_test "7. Stress Test" "/run/stress-test"
    sleep 2

    run_test "8. Flapping Monitor" "/run/flapping-monitor"
    sleep 2

    run_test "9. Queue Overflow" "/run/queue-overflow"
    sleep 2

    run_test "10. Failing Tasks" "/run/failing-tasks"
    sleep 2

    run_test "11. Cascade Kill" "/run/cascade-kill"
    sleep 2

    run_test "12. Shutdown Under Load" "/run/shutdown-under-load"

    echo -e "${BLUE}=====================================${NC}"
    echo "All tests completed!"
    echo -e "${BLUE}=====================================${NC}"

else
    # Run specific test
    case $1 in
        1|normal)
            run_test "Normal Operation" "/run/normal-operation"
            ;;
        2|spike)
            run_test "Resource Spike" "/run/resource-spike"
            ;;
        3|sustained)
            run_test "Sustained Load" "/run/sustained-load"
            ;;
        4|memory)
            run_test "Memory Pressure" "/run/memory-pressure"
            ;;
        5|killing)
            run_test "Task Killing" "/run/task-killing"
            ;;
        6|priority)
            run_test "Priority Scheduling" "/run/priority-scheduling"
            ;;
        7|stress)
            run_test "Stress Test" "/run/stress-test"
            ;;
        8|flapping)
            run_test "Flapping Monitor" "/run/flapping-monitor"
            ;;
        9|overflow)
            run_test "Queue Overflow" "/run/queue-overflow"
            ;;
        10|failing)
            run_test "Failing Tasks" "/run/failing-tasks"
            ;;
        11|cascade)
            run_test "Cascade Kill" "/run/cascade-kill"
            ;;
        12|shutdown)
            run_test "Shutdown Under Load" "/run/shutdown-under-load"
            ;;
        all)
            curl -s -X POST "$BASE_URL/run-all" | python3 -m json.tool
            ;;
        info)
            echo "System Information:"
            curl -s "$BASE_URL/system-info" | python3 -m json.tool
            ;;
        dashboard)
            echo -e "${BLUE}Opening dashboard...${NC}"
            if command -v open > /dev/null 2>&1; then
                open "http://localhost:8080/api/simulator/dashboard"
            elif command -v xdg-open > /dev/null 2>&1; then
                xdg-open "http://localhost:8080/api/simulator/dashboard"
            else
                echo "Dashboard URL: http://localhost:8080/api/simulator/dashboard"
            fi
            ;;
        start)
            echo -e "${BLUE}Starting application...${NC}"
            cd simulator
            mvn spring-boot:run
            ;;
        cpu-start)
            PERCENT=${2:-80}
            DURATION=${3:-10000}
            echo -e "${BLUE}Starting CPU load: ${PERCENT}% for ${DURATION}ms${NC}"
            curl -X POST "$BASE_URL/load/cpu/start?targetPercent=$PERCENT&durationMs=$DURATION"
            echo ""
            ;;
        cpu-stop)
            echo -e "${BLUE}Stopping CPU load${NC}"
            curl -X POST "$BASE_URL/load/cpu/stop"
            echo ""
            ;;
        memory-start)
            PERCENT=${2:-70}
            DURATION=${3:-10000}
            echo -e "${BLUE}Starting memory load: ${PERCENT}% for ${DURATION}ms${NC}"
            curl -X POST "$BASE_URL/load/memory/start?targetPercent=$PERCENT&durationMs=$DURATION"
            echo ""
            ;;
        memory-stop)
            echo -e "${BLUE}Stopping memory load${NC}"
            curl -X POST "$BASE_URL/load/memory/stop"
            echo ""
            ;;
        load-status)
            echo "Load Status:"
            curl -s "$BASE_URL/load/status" | python3 -m json.tool
            ;;
        *)
            echo "Usage: $0 [command] [options]"
            echo ""
            echo "Commands:"
            echo "  dashboard           - Open monitoring dashboard in browser"
            echo "  start               - Start the Spring Boot application"
            echo ""
            echo "Tests:"
            echo "  1 or normal         - Normal Operation"
            echo "  2 or spike          - Resource Spike"
            echo "  3 or sustained      - Sustained Load"
            echo "  4 or memory         - Memory Pressure"
            echo "  5 or killing        - Task Killing"
            echo "  6 or priority       - Priority Scheduling"
            echo "  7 or stress         - Stress Test"
            echo "  8 or flapping       - Flapping Monitor (edge case)"
            echo "  9 or overflow       - Queue Overflow (edge case)"
            echo "  10 or failing       - Failing Tasks (edge case)"
            echo "  11 or cascade       - Cascade Kill (edge case)"
            echo "  12 or shutdown      - Shutdown Under Load (edge case)"
            echo "  all                 - Run all tests (with full results)"
            echo ""
            echo "Load Control:"
            echo "  cpu-start [%] [ms]  - Start CPU load (default: 80% for 10s)"
            echo "  cpu-stop            - Stop CPU load"
            echo "  memory-start [%] [ms] - Start memory load (default: 70% for 10s)"
            echo "  memory-stop         - Stop memory load"
            echo "  load-status         - Show current load status"
            echo ""
            echo "Info:"
            echo "  info                - Show system information"
            echo ""
            echo "Examples:"
            echo "  $0 dashboard        # Open dashboard"
            echo "  $0 cpu-start 90 5000  # 90% CPU for 5 seconds"
            echo "  $0 memory-start 75 8000  # 75% memory for 8 seconds"
            echo ""
            echo "No argument runs all tests sequentially"
            exit 1
            ;;
    esac
fi

